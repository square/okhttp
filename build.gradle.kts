import java.net.URI
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
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

allprojects {
  group = "com.squareup.okhttp3"
  project.ext["artifactId"] = Projects.publishedArtifactId(project.name)
  version = "5.0.0-SNAPSHOT"

  repositories {
    mavenCentral()
    google()
    maven(url = "https://dl.bintray.com/kotlin/dokka")
  }

  val downloadDependencies by tasks.creating {
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

  apply(plugin = "org.jetbrains.kotlin.jvm")
  apply(plugin = "checkstyle")
  apply(plugin = "com.diffplug.spotless")
  apply(plugin = "ru.vyarus.animalsniffer")
  apply(plugin = "org.jetbrains.dokka")
  apply(plugin = "biz.aQute.bnd.builder")

  tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
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

  val checkstyleConfig by configurations.creating
  dependencies {
    checkstyleConfig(Dependencies.checkStyle) {
      isTransitive = false
    }
  }

  afterEvaluate {
    configure<CheckstyleExtension> {
      config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
      toolVersion = Versions.checkStyle
      sourceSets = listOf(project.sourceSets.getByName("main"))
    }
  }

  // Animal Sniffer confirms we generally don't use APIs not on Java 8.
  configure<AnimalSnifferExtension> {
    annotation = "okhttp3.internal.SuppressSignatureCheck"
    sourceSets = listOf(project.sourceSets.getByName("main"))
  }
  val signature by configurations.getting
  dependencies {
    signature(Dependencies.signatureAndroid21)
    signature(Dependencies.signatureJava18)
  }

  tasks.withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = "1.8"
      freeCompilerArgs = listOf(
        "-Xjvm-default=compatibility",
        "-Xopt-in=kotlin.RequiresOptIn"
      )
    }
  }

  val platform = System.getProperty("okhttp.platform", "jdk9")
  val testJavaVersion = System.getProperty("test.java.version", "11").toInt()

  val testRuntimeOnly by configurations.getting
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
    val alpnBootVersion = Alpn.alpnBootVersion()
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

  tasks.withType<DokkaTask> {
    configuration {
      reportUndocumented = false
      skipDeprecated = true
      jdkVersion = 8
      perPackageOption {
        prefix = "okhttp3.internal"
        suppress = true
      }
      perPackageOption {
        prefix = "mockwebserver3.internal"
        suppress = true
      }
      if (project.file("Module.md").exists()) {
        includes = listOf("Module.md")
      }
      externalDocumentationLink {
        url = URL("https://square.github.io/okio/2.x/okio/")
        packageListUrl = URL("https://square.github.io/okio/2.x/okio/package-list")
      }
    }
  }
}

/** Configure publishing and signing for published Java and JavaPlatform subprojects. */
subprojects {
  val project = this@subprojects
  if (project.ext.get("artifactId") == null) return@subprojects
  val bom = project.ext["artifactId"] == "okhttp-bom"

  if (bom) {
    apply(plugin = "java-platform")
  }

  apply(plugin = "maven-publish")
  apply(plugin = "signing")

  configure<PublishingExtension> {
    if (!bom) {
      configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
      }
    }

    publications {
      val maven by creating(MavenPublication::class) {
        groupId = project.group.toString()
        artifactId = project.ext["artifactId"].toString()
        version = project.version.toString()
        if (bom) {
          from(components.getByName("javaPlatform"))
        } else {
          from(components.getByName("java"))
        }
        pom {
          name.set(project.name)
          description.set("Square’s meticulous HTTP client for Java and Kotlin.")
          url.set("https://square.github.io/okhttp/")
          licenses {
            license {
              name.set("The Apache Software License, Version 2.0")
              url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          developers {
            developer {
              name.set("Square, Inc.")
            }
          }
          scm {
            connection.set("scm:git:https://github.com/square/okhttp.git")
            developerConnection.set("scm:git:ssh://git@github.com/square/okhttp.git")
            url.set("https://github.com/square/okhttp")
          }
        }
      }
    }

    repositories {
      maven {
        name = "mavencentral"
        url = URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
        credentials {
          username = System.getenv("SONATYPE_NEXUS_USERNAME")
          password = System.getenv("SONATYPE_NEXUS_PASSWORD")
        }
      }
    }
  }

  val publishing = extensions.getByType<PublishingExtension>()
  configure<SigningExtension> {
    sign(publishing.publications.getByName("maven"))
  }
}

tasks.withType<Wrapper> {
  distributionType = Wrapper.DistributionType.ALL
}
