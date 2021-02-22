rootProject.name = "okhttp-parent"

include(":mockwebserver")
include(":mockwebserver-deprecated")
include(":mockwebserver-junit4")
include(":mockwebserver-junit5")

include(":native-image-tests")

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
