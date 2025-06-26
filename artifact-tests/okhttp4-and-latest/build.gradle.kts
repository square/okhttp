plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

subprojects {
  repositories {
    // The latest 4.x from Maven Central.
    mavenCentral()

    // 5.x from a local build.
    maven(url = rootProject.layout.buildDirectory.asFile.get().resolve("../../../build/localMaven").toURI())
  }

  @Suppress("UnstableApiUsage")
  plugins.withType<TestSuiteBasePlugin> {
    extensions.configure<TestingExtension> {
      suites.withType<JvmTestSuite>().configureEach {
        useKotlinTest()
      }
    }
  }
}
