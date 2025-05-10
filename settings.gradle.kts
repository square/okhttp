import java.util.Properties

rootProject.name = "okhttp-parent"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version("0.10.0")
}

include(":mockwebserver")
project(":mockwebserver").name = "mockwebserver3"
include(":mockwebserver-deprecated")
project(":mockwebserver-deprecated").name = "mockwebserver"
include(":mockwebserver-junit4")
project(":mockwebserver-junit4").name = "mockwebserver3-junit4"
include(":mockwebserver-junit5")
project(":mockwebserver-junit5").name = "mockwebserver3-junit5"

val androidBuild: String by settings
val graalBuild: String by settings
val loomBuild: String by settings

if (androidBuild.toBoolean()) {
  include(":regression-test")
}

if (graalBuild.toBoolean()) {
  include(":native-image-tests")
}

include(":okcurl")
include(":okhttp")
include(":okhttp-bom")
include(":okhttp-brotli")
include(":okhttp-coroutines")
include(":okhttp-dnsoverhttps")
include(":okhttp-hpacktests")
include(":okhttp-idna-mapping-table")
include(":okhttp-java-net-cookiejar")
include(":okhttp-logging-interceptor")
include(":okhttp-osgi-tests")
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
include(":samples:tlssurvey")
include(":samples:unixdomainsockets")
include(":container-tests")

project(":okhttp-logging-interceptor").name = "logging-interceptor"

val androidHome = System.getenv("ANDROID_HOME")
val localProperties = Properties().apply {
  val file = File("local.properties")
  if (file.exists()) {
    load(file.inputStream())
  }
}
val sdkDir = localProperties.getProperty("sdk.dir")
if (androidHome != null || sdkDir != null) {
  include(":android-test")
  include(":android-test-app")
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
