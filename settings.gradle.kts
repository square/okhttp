rootProject.name = "okhttp-parent"

plugins {
  id("com.gradle.enterprise").version("3.6.2")
  id("com.gradle.common-custom-user-data-gradle-plugin").version("1.3")
}

gradleEnterprise {
  server = "https://ec2-18-205-192-234.compute-1.amazonaws.com"
  allowUntrustedServer = true
  buildScan {
    publishAlways()
    isCaptureTaskInputFiles = true
  }
}

buildCache {
  local {
    removeUnusedEntriesAfterDays = 1
  }
  remote<HttpBuildCache> {
    isEnabled = false
  }
}

include(":mockwebserver")
include(":mockwebserver-deprecated")
include(":mockwebserver-junit4")
include(":mockwebserver-junit5")

val androidBuild: String? by settings
val graalBuild: String? by settings

if (androidBuild != null) {
  include(":android-test")
  include(":regression-test")
}

if (graalBuild != null) {
  include(":native-image-tests")
}

include(":okcurl")
include(":okhttp")
include(":okhttp-bom")
include(":okhttp-brotli")
include(":okhttp-dnsoverhttps")
include(":okhttp-hpacktests")
include(":okhttp-logging-interceptor")
include(":okhttp-sse")
include(":okhttp-testing-support")
include(":okhttp-tls")
include(":okhttp-urlconnection")
include(":samples:compare")
include(":samples:crawler")
include(":samples:guide")
include(":samples:simple-client")
include(":samples:slack")
include(":samples:static-server")
include(":samples:unixdomainsockets")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://dl.bintray.com/kotlin/dokka")
        google()
    }
}
