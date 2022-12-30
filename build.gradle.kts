import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import groovy.util.Node
import groovy.util.NodeList
import java.net.URL
import kotlinx.validation.ApiValidationExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

buildscript {
  dependencies {
    classpath(libs.gradlePlugin.dokka)
    classpath(libs.gradlePlugin.kotlin)
    classpath(libs.gradlePlugin.androidJunit5)
    classpath(libs.gradlePlugin.android)
    classpath(libs.gradlePlugin.graal)
    classpath(libs.gradlePlugin.bnd)
    classpath(libs.gradlePlugin.shadow)
    classpath(libs.gradlePlugin.animalsniffer)
    classpath(libs.gradlePlugin.errorprone)
    classpath(libs.gradlePlugin.spotless)
    classpath(libs.gradlePlugin.mavenPublish)
    classpath(libs.gradlePlugin.binaryCompatibilityValidator)
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

apply(plugin = "org.jetbrains.dokka")

allprojects {
  group = "com.squareup.okhttp3"
  version = "5.0.0-SNAPSHOT"

  repositories {
    mavenCentral()
    google()
  }

  tasks.create("downloadDependencies") {
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

/** Configure building for Java+Kotlin projects. */
subprojects {
  val project = this@subprojects
  if (project.name == "okhttp-bom") return@subprojects

  if (project.name == "okhttp-android") return@subprojects
  if (project.name == "android-test") return@subprojects
  if (project.name == "regression-test") return@subprojects

  apply(plugin = "checkstyle")
  apply(plugin = "ru.vyarus.animalsniffer")
  apply(plugin = "biz.aQute.bnd.builder")

  tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.toString()
  }

  configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(11))
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

  configure<CheckstyleExtension> {
    config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
    toolVersion = rootProject.libs.versions.checkStyle.get()
    sourceSets = listOf(project.sourceSets["main"])
  }

  // Animal Sniffer confirms we generally don't use APIs not on Java 8.
  configure<AnimalSnifferExtension> {
    annotation = "okhttp3.internal.SuppressSignatureCheck"
    sourceSets = listOf(project.sourceSets["main"])
  }

  val signature: Configuration by configurations.getting
  dependencies {
    signature(rootProject.libs.signature.android.apilevel21)
    signature(rootProject.libs.codehaus.signature.java18)
  }

  tasks.withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_1_8.toString()
      freeCompilerArgs = listOf(
        "-Xjvm-default=all",
      )
    }
  }

  val platform = System.getProperty("okhttp.platform", "jdk9")
  val testJavaVersion = System.getProperty("test.java.version", "11").toInt()

  val testRuntimeOnly: Configuration by configurations.getting
  dependencies {
    testRuntimeOnly(rootProject.libs.junit.jupiter.engine)
    testRuntimeOnly(rootProject.libs.junit.vintage.engine)
  }

  tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs = jvmArgs!! + listOf(
      "-Dokhttp.platform=$platform",
      "-XX:+HeapDumpOnOutOfMemoryError"
    )

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

  if (platform == "jdk8alpn") {
    // Add alpn-boot on Java 8 so we can use HTTP/2 without a stable API.
    val alpnBootVersion = alpnBootVersion()
    if (alpnBootVersion != null) {
      val alpnBootJar = configurations.detachedConfiguration(
        dependencies.create("org.mortbay.jetty.alpn:alpn-boot:$alpnBootVersion")
      ).singleFile
      tasks.withType<Test> {
        jvmArgs = jvmArgs!! + listOf("-Xbootclasspath/p:${alpnBootJar}")
      }
    }
  } else if (platform == "conscrypt") {
    dependencies {
      testRuntimeOnly(rootProject.libs.conscrypt.openjdk)
    }
  } else if (platform == "openjsse") {
    dependencies {
      testRuntimeOnly(rootProject.libs.openjsse)
    }
  }

  tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
  }
}

/** Configure publishing and signing for published Java and JavaPlatform subprojects. */
subprojects {
  tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.configureEach {
      reportUndocumented.set(false)
      skipDeprecated.set(true)
      jdkVersion.set(8)
      perPackageOption {
        matchingRegex.set("okhttp3\\.internal.*")
        suppress.set(true)
      }
      perPackageOption {
        matchingRegex.set("mockwebserver3\\.internal.*")
        suppress.set(true)
      }
      if (project.file("Module.md").exists()) {
        includes.from(project.file("Module.md"))
      }
      externalDocumentationLink {
        url.set(URL("https://square.github.io/okio/2.x/okio/"))
        packageListUrl.set(URL("https://square.github.io/okio/2.x/okio/package-list"))
      }
    }
    if (name == "dokkaGfm") {
      outputDirectory.set(file("${rootDir}/docs/4.x"))
    }
  }

  plugins.withId("com.vanniktech.maven.publish.base") {
    val publishingExtension = extensions.getByType(PublishingExtension::class.java)
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

      // Configure the kotlinMultiplatform artifact to depend on the JVM artifact in pom.xml only.
      // This hack allows Maven users to continue using our original OkHttp artifact names (like
      // com.squareup.okhttp3:okhttp:5.x.y) even though we changed that artifact from JVM-only
      // to Kotlin Multiplatform. Note that module.json doesn't need this hack.
      val mavenPublications = publishingExtension.publications.withType<MavenPublication>()
      mavenPublications.configureEach {
        if (name != "jvm") return@configureEach
        val jvmPublication = this
        val kmpPublication = mavenPublications.getByName("kotlinMultiplatform")
        kmpPublication.pom.withXml {
          val root = asNode()
          val dependencies = (root["dependencies"] as NodeList).firstOrNull() as Node?
            ?: root.appendNode("dependencies")
          for (child in dependencies.children().toList()) {
            dependencies.remove(child as Node)
          }
          dependencies.appendNode("dependency").apply {
            appendNode("groupId", jvmPublication.groupId)
            appendNode("artifactId", jvmPublication.artifactId)
            appendNode("version", jvmPublication.version)
            appendNode("scope", "compile")
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

tasks.wrapper {
  distributionType = Wrapper.DistributionType.ALL
}
