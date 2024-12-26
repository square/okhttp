package okhttp3.internal.platform

import java.security.Security

actual object PlatformRegistry {
  private val isConscryptPreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "Conscrypt" == preferredProvider
    }

  private val isOpenJSSEPreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "OpenJSSE" == preferredProvider
    }

  private val isBouncyCastlePreferred: Boolean
    get() {
      val preferredProvider = Security.getProviders()[0].name
      return "BC" == preferredProvider
    }

  actual fun findPlatform(): Platform {
    if (isConscryptPreferred) {
      val conscrypt = ConscryptPlatform.buildIfSupported()

      if (conscrypt != null) {
        return conscrypt
      }
    }

    if (isBouncyCastlePreferred) {
      val bc = BouncyCastlePlatform.buildIfSupported()

      if (bc != null) {
        return bc
      }
    }

    if (isOpenJSSEPreferred) {
      val openJSSE = OpenJSSEPlatform.buildIfSupported()

      if (openJSSE != null) {
        return openJSSE
      }
    }

    // An Oracle JDK 9 like OpenJDK, or JDK 8 251+.
    val jdk9 = Jdk9Platform.buildIfSupported()

    if (jdk9 != null) {
      return jdk9
    }

    // An Oracle JDK 8 like OpenJDK, pre 251.
    val jdkWithJettyBoot = Jdk8WithJettyBootPlatform.buildIfSupported()

    if (jdkWithJettyBoot != null) {
      return jdkWithJettyBoot
    }

    return Platform()
  }

  actual val isAndroid: Boolean
    get() = false
}
