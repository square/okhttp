package okhttp3

import okhttp3.internal.platform.Platform
import org.conscrypt.Conscrypt
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.Assume.assumeThat
import org.junit.rules.ExternalResource
import java.security.Security

open class PlatformRule @JvmOverloads constructor(
  val requiredPlatformName: String? = null,
  val platform: Platform? = null
) : ExternalResource() {
  override fun before() {
    if (requiredPlatformName != null) {
      assumeThat(getPlatform(), equalTo(requiredPlatformName))
    }

    if (platform != null) {
      Platform.reset(platform)
    } else {
      Platform.reset()
    }
  }

  override fun after() {
    Platform.reset()
  }

  fun isConscrypt() = getPlatform() == "conscrypt"
  fun isJdk9() = getPlatform() == "jdk9"
  fun isLegacy() = getPlatform() == "platform"
  fun assumeConscrypt() {
    assumeThat(getPlatform(), equalTo("conscrypt"))
  }
  fun assumeJdk9() {
    assumeThat(getPlatform(), equalTo("jdk9"))
  }
  fun assumeLegacy() {
    assumeThat(getPlatform(), equalTo("platform"))
  }
  fun assumeNotConscrypt() {
    assumeThat(getPlatform(), not("conscrypt"))
  }
  fun assumeNotJdk9() {
    assumeThat(getPlatform(), not("jdk9"))
  }
  fun assumeNotLegacy() {
    assumeThat(getPlatform(), not("platform"))
  }

  companion object {
    init {
      if (getPlatform() == "conscrypt" && Security.getProviders()[0].name != "Conscrypt") {
        val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
        Security.insertProviderAt(provider, 1)
      }
    }

    @JvmStatic
    fun getPlatform(): String = System.getProperty("okhttp.platform", "platform")

    @JvmStatic
    fun conscrypt() = PlatformRule("conscrypt")

    @JvmStatic
    fun jdk9() = PlatformRule("jdk9")

    @JvmStatic
    fun legacy() = PlatformRule("platform")
  }
}