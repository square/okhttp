@file:Suppress("UnstableApiUsage")

import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import kotlinx.validation.ApiValidationExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.utils.addExtendsFromRelation
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import java.net.URI

buildscript {
  dependencies {
    classpath(libs.gradlePlugin.dokka)
    classpath(libs.gradlePlugin.kotlin)
    classpath(libs.gradlePlugin.kotlinSerialization)
    classpath(libs.gradlePlugin.androidJunit5)
    classpath(libs.gradlePlugin.android)
    classpath(libs.gradlePlugin.bnd)
    classpath(libs.gradlePlugin.shadow)
    classpath(libs.gradlePlugin.animalsniffer)
    classpath(libs.gradlePlugin.errorprone)
    classpath(libs.gradlePlugin.spotless)
    classpath(libs.gradlePlugin.mavenPublish)
    classpath(libs.gradlePlugin.binaryCompatibilityValidator)
    classpath(libs.gradlePlugin.mavenSympathy)
    classpath(libs.gradlePlugin.graalvmBuildTools)
    classpath(libs.gradlePlugin.ksp)
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

apply(plugin = "org.jetbrains.dokka")
apply(plugin = "com.diffplug.spotless")

configure<SpotlessExtension> {
  kotlin {
    target("**/*.kt")
    targetExclude("**/kotlinTemplates/**/*.kt")
    ktlint()
  }
}

allprojects {
  group = "com.squareup.okhttp3"
  version = "5.0.0-SNAPSHOT"

  repositories {
    mavenCentral()
    google()
  }

  tasks.register("downloadDependencies") {
    description = "Download all dependencies to the Gradle cache"
    doLast {
      for (configuration in configurations) {
        if (configuration.isCanBeResolved) {
          configuration.files
        }
      }
    }
  }

  normalization {
    runtimeClasspath {
      metaInf {
        ignoreAttribute("Bnd-LastModified")
      }
    }
  }
}

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

/** Configure building for Java+Kotlin projects. */
subprojects {
  val project = this@subprojects
  if (project.name == "okhttp-bom") return@subprojects

  if (project.name == "okhttp-android") return@subprojects
  if (project.name == "android-test") return@subprojects
  if (project.name == "regression-test") return@subprojects
  if (project.name == "android-test-app") return@subprojects
  if (project.name == "container-tests") return@subprojects

  apply(plugin = "checkstyle")
  apply(plugin = "ru.vyarus.animalsniffer")

  // The 'java' plugin has been applied, but it is not compatible with the Android plugins.
  // These are applied inside the okhttp module for that case specifically
  if (project.name != "okhttp") {
    apply(plugin = "biz.aQute.bnd.builder")
    apply(plugin = "io.github.usefulness.maven-sympathy")
  }

  // Skip samples parent
  if (project.buildFile.exists() && project.name != "okhttp") {
    apply(plugin = "com.android.lint")

    dependencies {
      "lintChecks"(rootProject.libs.androidx.lint.gradle)
    }
  }

  tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.toString()
  }

  if (plugins.hasPlugin(JavaBasePlugin::class.java)) {
    extensions.configure<JavaPluginExtension> {
      toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
      }
    }
  }

  tasks.withType<Checkstyle>().configureEach {
    exclude("**/CipherSuite.java")
  }

  val checkstyleConfig: Configuration by configurations.creating
  dependencies {
    checkstyleConfig(rootProject.libs.checkStyle) {
      isTransitive = false
    }
  }

  val androidSignature by configurations.creating
  val jvmSignature by configurations.creating

  // Handled in :okhttp directly
  if (project.name != "okhttp") {
    configure<CheckstyleExtension> {
      config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
      toolVersion = rootProject.libs.versions.checkStyle.get()
      sourceSets = listOf(project.sourceSets["main"])
    }

    // Animal Sniffer confirms we generally don't use APIs not on Java 8.
    configure<AnimalSnifferExtension> {
      annotation = "okhttp3.internal.SuppressSignatureCheck"
      sourceSets = listOf(project.sourceSets["main"])
      signatures = androidSignature + jvmSignature
      failWithoutSignatures = false
    }
  }

  dependencies {
    // No dependency requirements for testing-support.
    if (project.name == "okhttp-testing-support") return@dependencies

    // okhttp configured specifically.
    if (project.name == "okhttp") return@dependencies

    if (project.name == "mockwebserver3-junit5") {
      // JUnit 5's APIs need java.util.function.Function and java.util.Optional from API 24.
      androidSignature(rootProject.libs.signature.android.apilevel24) { artifact { type = "signature" } }
    } else {
      // Everything else requires Android API 21+.
      androidSignature(rootProject.libs.signature.android.apilevel21) { artifact { type = "signature" } }
    }

    // OkHttp requires Java 8+.
    jvmSignature(rootProject.libs.codehaus.signature.java18) { artifact { type = "signature" } }
  }

  val javaVersionSetting =
    if (testJavaVersion > 8 && (project.name == "okcurl" || project.name == "native-image-tests")) {
      // Depends on native-image-tools which is 11+, but avoids on Java 8 tests
      "11"
    } else {
      "1.8"
    }

  val projectJvmTarget = JvmTarget.fromTarget(javaVersionSetting)
  val projectJavaVersion = JavaVersion.toVersion(javaVersionSetting)

  tasks.withType<KotlinCompile> {
    compilerOptions {
      jvmTarget.set(projectJvmTarget)
      freeCompilerArgs = listOf(
        "-Xjvm-default=all",
      )
    }
  }

  val platform = System.getProperty("okhttp.platform", "jdk9")
  val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

  if (project.name != "okhttp") {
    val testRuntimeOnly: Configuration by configurations.getting
    dependencies {
      // https://junit.org/junit5/docs/current/user-guide/#running-tests-build-gradle-bom
      testRuntimeOnly(rootProject.libs.junit.jupiter.engine)
      testRuntimeOnly(rootProject.libs.junit.vintage.engine)
      testRuntimeOnly(rootProject.libs.junit.platform.launcher)
    }
  }

  tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
      "-Dokhttp.platform=$platform",
    )

    if (platform == "loom") {
      jvmArgs(
        "-Djdk.tracePinnedThreads=short",
      )
    }

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    javaLauncher.set(javaToolchains.launcherFor {
      languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
    })

    maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
    testLogging {
      exceptionFormat = TestExceptionFormat.FULL
    }

    systemProperty("okhttp.platform", platform)
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  }

  // https://publicobject.com/2023/04/16/read-a-project-file-in-a-kotlin-multiplatform-test/
  tasks.withType<Test>().configureEach {
    environment("OKHTTP_ROOT", rootDir)
  }
  tasks.withType<KotlinJvmTest>().configureEach {
    environment("OKHTTP_ROOT", rootDir)
  }

  if (project.name != "okhttp") {
    if (platform == "jdk8alpn") {
      // Add alpn-boot on Java 8 so we can use HTTP/2 without a stable API.
      val alpnBootVersion = alpnBootVersion()
      if (alpnBootVersion != null) {
        val alpnBootJar = configurations.detachedConfiguration(
          dependencies.create("org.mortbay.jetty.alpn:alpn-boot:$alpnBootVersion")
        ).singleFile
        tasks.withType<Test> {
          jvmArgs("-Xbootclasspath/p:${alpnBootJar}")
        }
      }
    }
  }

  tasks.withType<JavaCompile> {
    sourceCompatibility = projectJavaVersion.toString()
    targetCompatibility = projectJavaVersion.toString()
  }
}

// Opt-in to @ExperimentalOkHttpApi everywhere.
subprojects {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    kotlinExtension.sourceSets.configureEach {
      languageSettings.optIn("okhttp3.ExperimentalOkHttpApi")
    }
  }
  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    kotlinExtension.sourceSets.configureEach {
      languageSettings.optIn("okhttp3.ExperimentalOkHttpApi")
    }
  }
  plugins.withId("org.jetbrains.kotlin.android") {
    kotlinExtension.sourceSets.configureEach {
      languageSettings.optIn("okhttp3.ExperimentalOkHttpApi")
    }
  }

  // From https://www.liutikas.net/2025/01/12/Kotlin-Library-Friends.html

    // Create configurations we can use to track friend libraries
  configurations {
    val friendsApi = register("friendsApi") {
      isCanBeResolved = true
      isCanBeConsumed = false
      isTransitive = true
    }
    val friendsImplementation = register("friendsImplementation") {
      isCanBeResolved = true
      isCanBeConsumed = false
      isTransitive = false
    }
    val friendsTestImplementation = register("friendsTestImplementation") {
      isCanBeResolved = true
      isCanBeConsumed = false
      isTransitive = false
    }
    configurations.configureEach {
      if (name == "implementation") {
        extendsFrom(friendsApi.get(), friendsImplementation.get())
      }
      if (name == "api") {
        extendsFrom(friendsApi.get())
      }
      if (name == "testImplementation") {
        extendsFrom(friendsTestImplementation.get())
      }
    }
  }

    // Make these libraries friends :)
    tasks.withType<KotlinCompile>().configureEach {
      configurations.findByName("friendsApi")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
      }
      configurations.findByName("friendsImplementation")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
      }
      configurations.findByName("friendsTestImplementation")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
      }
    }
}

/** Configure publishing and signing for published Java and JavaPlatform subprojects. */
subprojects {
  tasks.withType<DokkaTaskPartial>().configureEach {
    dokkaSourceSets.configureEach {
      reportUndocumented.set(false)
      skipDeprecated.set(true)
      jdkVersion.set(8)
      perPackageOption {
        matchingRegex.set(".*\\.internal.*")
        suppress.set(true)
      }
      if (project.file("Module.md").exists()) {
        includes.from(project.file("Module.md"))
      }
      externalDocumentationLink {
        url.set(URI.create("https://square.github.io/okio/3.x/okio/").toURL())
        packageListUrl.set(URI.create("https://square.github.io/okio/3.x/okio/okio/package-list").toURL())
      }
    }
  }

  plugins.withId("com.vanniktech.maven.publish.base") {
    configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.S01, automaticRelease = true)
      signAllPublications()
      pom {
        name.set(project.name)
        description.set("Square’s meticulous HTTP client for Java and Kotlin.")
        url.set("https://square.github.io/okhttp/")
        licenses {
          license {
            name.set("The Apache Software License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }
        scm {
          connection.set("scm:git:https://github.com/square/okhttp.git")
          developerConnection.set("scm:git:ssh://git@github.com/square/okhttp.git")
          url.set("https://github.com/square/okhttp")
        }
        developers {
          developer {
            name.set("Square, Inc.")
          }
        }
      }
    }
  }

  plugins.withId("binary-compatibility-validator") {
    configure<ApiValidationExtension> {
      ignoredPackages += "okhttp3.logging.internal"
      ignoredPackages += "mockwebserver3.internal"
      ignoredPackages += "okhttp3.internal"
      ignoredPackages += "mockwebserver3.junit5.internal"
      ignoredPackages += "okhttp3.brotli.internal"
      ignoredPackages += "okhttp3.sse.internal"
      ignoredPackages += "okhttp3.tls.internal"
    }
  }
}

plugins.withId("org.jetbrains.kotlin.jvm") {
  val test = tasks.named("test")
  tasks.register("jvmTest") {
    description = "Get 'gradlew jvmTest' to run the tests of JVM-only modules"
    dependsOn(test)
  }
}

tasks.wrapper {
  distributionType = Wrapper.DistributionType.ALL
}
