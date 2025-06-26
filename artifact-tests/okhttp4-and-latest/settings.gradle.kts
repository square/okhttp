import org.gradle.kotlin.dsl.mavenCentral

dependencyResolutionManagement {
  repositories {
    // The latest 4.x from Maven Central.
    mavenCentral()

    // 5.x from a local build.
    maven(url = "../../build/localMaven")
  }
}

pluginManagement {
  repositories {
    mavenCentral()
  }
}


include("classpathscanner")
include("lib-depends-on-4x-and-latest")
include("lib-depends-on-4x")
include("lib-depends-on-latest")
