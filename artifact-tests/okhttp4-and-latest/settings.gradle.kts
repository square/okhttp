import org.gradle.kotlin.dsl.mavenCentral

dependencyResolutionManagement {
  repositories {
    // The latest 4.x from Maven Central.
    mavenCentral()
    google()

    // 5.x from a local build.
    maven(url = "../../build/localMaven")
  }
}

pluginManagement {
  repositories {
    mavenCentral()
    google()
  }
}


include("android-depends-on-ld-and-latest")
include("android-depends-on-lots")
include("android-depends-on-wire-and-latest")
include("classpathscanner")
include("lib-depends-on-4x-and-latest")
include("lib-depends-on-4x-and-latest-jvm")
include("lib-depends-on-4x")
include("lib-depends-on-ld-and-latest")
include("lib-depends-on-latest")
include("lib-depends-on-latest-jvm")
include("lib-depends-on-retrofit-and-latest")
include("lib-depends-on-wire-and-latest")

