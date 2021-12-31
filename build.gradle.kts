import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import java.net.URL
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

buildscript {
  dependencies {
    classpath(Dependencies.kotlinPlugin)
    classpath(Dependencies.dokkaPlugin)
    classpath(Dependencies.androidPlugin)
    classpath(Dependencies.androidJunit5Plugin)
    classpath(Dependencies.graalPlugin)
    classpath(Dependencies.bndPlugin)
    classpath(Dependencies.shadowPlugin)
    classpath(Dependencies.japicmpPlugin)
    classpath(Dependencies.animalsnifferPlugin)
    classpath(Dependencies.errorpronePlugin)
    classpath(Dependencies.spotlessPlugin)
    classpath(Dependencies.vanniktechPublishPlugin)
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

apply(plugin = "com.vanniktech.maven.publish.base")

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
  if (project.name == "android-test") return@subprojects
  if (project.name == "okhttp-bom") return@subprojects
  if (project.name == "regression-test") return@subprojects

  apply(plugin = "checkstyle")
  apply(plugin = "com.diffplug.spotless")
  apply(plugin = "ru.vyarus.animalsniffer")
  apply(plugin = "org.jetbrains.dokka")
  apply(plugin = "biz.aQute.bnd.builder")

  tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.toString()
  }

  configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(11))
      vendor.set(JvmVendorSpec.ADOPTOPENJDK)
    }
  }

  tasks.withType<Checkstyle>().configureEach {
    exclude("**/CipherSuite.java")
  }

  val checkstyleConfig: Configuration by configurations.creating
  dependencies {
    checkstyleConfig(Dependencies.checkStyle) {
      isTransitive = false
    }
  }

  afterEvaluate {
    configure<CheckstyleExtension> {
      config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
      toolVersion = Versions.checkStyle
      sourceSets = listOf(project.sourceSets["main"])
    }
  }

  // Animal Sniffer confirms we generally don't use APIs not on Java 8.
  configure<AnimalSnifferExtension> {
    annotation = "okhttp3.internal.SuppressSignatureCheck"
    sourceSets = listOf(project.sourceSets["main"])
  }
  val signature: Configuration by configurations.getting
  dependencies {
    signature(Dependencies.signatureAndroid21)
    signature(Dependencies.signatureJava18)
  }

  tasks.withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_1_8.toString()
      freeCompilerArgs = listOf(
        "-Xjvm-default=compatibility",
        "-Xopt-in=kotlin.RequiresOptIn"
      )
    }
  }

  val platform = System.getProperty("okhttp.platform", "jdk9")
  val testJavaVersion = System.getProperty("test.java.version", "11").toInt()

  val testRuntimeOnly: Configuration by configurations.getting
  dependencies {
    testRuntimeOnly(Dependencies.junit5JupiterEngine)
    testRuntimeOnly(Dependencies.junit5VintageEngine)
  }

  tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs = jvmArgs!! + listOf("-Dokhttp.platform=$platform")

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    javaLauncher.set(javaToolchains.launcherFor {
      languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
      vendor.set(JvmVendorSpec.ADOPTOPENJDK)
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
      testRuntimeOnly(Dependencies.conscrypt)
    }
  } else if (platform == "openjsse") {
    dependencies {
      testRuntimeOnly(Dependencies.openjsse)
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
  }

  plugins.withId("com.vanniktech.maven.publish.base") {
    configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.S01)
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
}

tasks.wrapper {
  distributionType = Wrapper.DistributionType.ALL
}
