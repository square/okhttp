rootProject.name = "okhttp-parent"

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
  include(":android-test")
  include(":regression-test")
}

if (graalBuild.toBoolean()) {
  include(":native-image-tests")
}

include(":okcurl")
include(":okhttp")
include(":okhttp-android")
include(":okhttp-bom")
include(":okhttp-brotli")
include(":okhttp-dnsoverhttps")
include(":okhttp-hpacktests")
include(":okhttp-idna-mapping-table")
include(":okhttp-logging-interceptor")
project(":okhttp-logging-interceptor").name = "logging-interceptor"
include(":okhttp-sse")
include(":okhttp-testing-support")
include(":okhttp-tls")
include(":okhttp-coroutines")
include(":okhttp-urlconnection")
include(":samples:compare")
include(":samples:crawler")
include(":samples:guide")
include(":samples:simple-client")
include(":samples:slack")
include(":samples:static-server")
include(":samples:tlssurvey")
include(":samples:unixdomainsockets")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
