/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.testing

import com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider
import com.amazon.corretto.crypto.provider.SelfTestStatus
import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Jdk8WithJettyBootPlatform
import okhttp3.internal.platform.Jdk9Platform
import okhttp3.internal.platform.OpenJSSEPlatform
import okhttp3.internal.platform.Platform
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.conscrypt.Conscrypt
import org.hamcrest.BaseMatcher
import org.hamcrest.CoreMatchers.anything
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.StringDescription
import org.hamcrest.TypeSafeMatcher
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.openjsse.net.ssl.OpenJSSE
import org.opentest4j.TestAbortedException
import java.lang.reflect.Method
import java.security.Security

/**
 * Marks a test as Platform aware, before the test runs a consistent Platform will be
 * established e.g. SecurityProvider for Conscrypt installed.
 *
 * Also allows a test file to state general platform assumptions, or for individual test.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
open class PlatformRule @JvmOverloads constructor(
  val requiredPlatformName: String? = null,
  val platform: Platform? = null
) : BeforeEachCallback, AfterEachCallback, InvocationInterceptor {
  private val versionChecks = mutableListOf<Pair<Matcher<out Any>, Matcher<out Any>>>()

  override fun beforeEach(context: ExtensionContext) {
    setupPlatform()
  }

  override fun afterEach(context: ExtensionContext) {
    resetPlatform()
  }

  override fun interceptTestMethod(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: ReflectiveInvocationContext<Method>,
    extensionContext: ExtensionContext
  ) {
    var failed = false
    try {
      invocation.proceed()
    } catch (e: TestAbortedException) {
      throw e
    } catch (e: Throwable) {
      failed = true
      rethrowIfNotExpected(e)
    } finally {
      resetPlatform()
    }
    if (!failed) {
      failIfExpected()
    }
  }

  fun setupPlatform() {
    if (requiredPlatformName != null) {
      assumeTrue(getPlatformSystemProperty() == requiredPlatformName)
    }

    if (platform != null) {
      Platform.resetForTests(platform)
    } else {
      Platform.resetForTests()
    }

    if (requiredPlatformName != null) {
      System.err.println("Running with ${Platform.get().javaClass.simpleName}")
    }
  }

  fun resetPlatform() {
    if (platform != null) {
      Platform.resetForTests()
    }
  }

  fun expectFailureOnConscryptPlatform() {
    expectFailure(platformMatches(CONSCRYPT_PROPERTY))
  }

  fun expectFailureOnCorrettoPlatform() {
    expectFailure(platformMatches(CORRETTO_PROPERTY))
  }

  fun expectFailureOnOpenJSSEPlatform() {
    expectFailure(platformMatches(OPENJSSE_PROPERTY))
  }

  fun expectFailureFromJdkVersion(majorVersion: Int) {
    expectFailure(fromMajor(majorVersion))
  }

  fun expectFailureOnJdkVersion(majorVersion: Int) {
    expectFailure(onMajor(majorVersion))
  }

  private fun expectFailure(
    versionMatcher: Matcher<out Any>,
    failureMatcher: Matcher<out Any> = anything()
  ) {
    versionChecks.add(Pair(versionMatcher, failureMatcher))
  }

  fun platformMatches(platform: String): Matcher<Any> = object : BaseMatcher<Any>() {
    override fun describeTo(description: Description) {
      description.appendText(platform)
    }

    override fun matches(item: Any?): Boolean {
      return getPlatformSystemProperty() == platform
    }
  }

  fun fromMajor(version: Int): Matcher<PlatformVersion> {
    return object : TypeSafeMatcher<PlatformVersion>() {
      override fun describeTo(description: Description) {
        description.appendText("JDK with version from $version")
      }

      override fun matchesSafely(item: PlatformVersion): Boolean {
        return item.majorVersion >= version
      }
    }
  }

  fun onMajor(version: Int): Matcher<PlatformVersion> {
    return object : TypeSafeMatcher<PlatformVersion>() {
      override fun describeTo(description: Description) {
        description.appendText("JDK with version $version")
      }

      override fun matchesSafely(item: PlatformVersion): Boolean {
        return item.majorVersion == version
      }
    }
  }

  fun rethrowIfNotExpected(e: Throwable) {
    versionChecks.forEach { (versionMatcher, failureMatcher) ->
      if (versionMatcher.matches(PlatformVersion) && failureMatcher.matches(e)) {
        return
      }
    }

    throw e
  }

  fun failIfExpected() {
    versionChecks.forEach { (versionMatcher, failureMatcher) ->
      if (versionMatcher.matches(PlatformVersion)) {
        val description = StringDescription()
        versionMatcher.describeTo(description)
        description.appendText(" expected to fail with exception that ")
        failureMatcher.describeTo(description)

        fail<Any>(description.toString())
      }
    }
  }

  fun isConscrypt() = getPlatformSystemProperty() == CONSCRYPT_PROPERTY

  fun isJdk9() = getPlatformSystemProperty() == JDK9_PROPERTY

  fun isJdk8() = getPlatformSystemProperty() == JDK8_PROPERTY

  fun isJdk8Alpn() = getPlatformSystemProperty() == JDK8_ALPN_PROPERTY

  fun isBouncyCastle() = getPlatformSystemProperty() == BOUNCYCASTLE_PROPERTY

  fun hasHttp2Support() = !isJdk8()

  fun assumeConscrypt() {
    assumeTrue(getPlatformSystemProperty() == CONSCRYPT_PROPERTY)
  }

  fun assumeJdk9() {
    assumeTrue(getPlatformSystemProperty() == JDK9_PROPERTY)
  }

  fun assumeOpenJSSE() {
    assumeTrue(getPlatformSystemProperty() == OPENJSSE_PROPERTY)
  }

  fun assumeJdk8() {
    assumeTrue(getPlatformSystemProperty() == JDK8_PROPERTY)
  }

  fun assumeJdk8Alpn() {
    assumeTrue(getPlatformSystemProperty() == JDK8_ALPN_PROPERTY)
  }

  fun assumeCorretto() {
    assumeTrue(getPlatformSystemProperty() == CORRETTO_PROPERTY)
  }

  fun assumeBouncyCastle() {
    assumeTrue(getPlatformSystemProperty() == BOUNCYCASTLE_PROPERTY)
  }

  fun assumeHttp2Support() {
    assumeTrue(getPlatformSystemProperty() != JDK8_PROPERTY)
  }

  fun assumeAndroid() {
    assumeTrue(Platform.isAndroid)
  }

  fun assumeNotConscrypt() {
    assumeTrue(getPlatformSystemProperty() != CONSCRYPT_PROPERTY)
  }

  fun assumeNotJdk9() {
    assumeTrue(getPlatformSystemProperty() != JDK9_PROPERTY)
  }

  fun assumeNotJdk8() {
    assumeTrue(getPlatformSystemProperty() != JDK8_PROPERTY)
  }

  fun assumeNotJdk8Alpn() {
    assumeTrue(getPlatformSystemProperty() != JDK8_ALPN_PROPERTY)
  }

  fun assumeNotOpenJSSE() {
    assumeTrue(getPlatformSystemProperty() != OPENJSSE_PROPERTY)
  }

  fun assumeNotCorretto() {
    assumeTrue(getPlatformSystemProperty() != CORRETTO_PROPERTY)
  }

  fun assumeNotBouncyCastle() {
    // Most failures are with MockWebServer
    // org.bouncycastle.tls.TlsFatalAlertReceived: handshake_failure(40)
    //        at org.bouncycastle.tls.TlsProtocol.handleAlertMessage(TlsProtocol.java:241)
    assumeTrue(getPlatformSystemProperty() != BOUNCYCASTLE_PROPERTY)
  }

  fun assumeNotHttp2Support() {
    assumeTrue(getPlatformSystemProperty() == JDK8_PROPERTY)
  }

  fun assumeJettyBootEnabled() {
    assumeTrue(isAlpnBootEnabled())
  }

  fun assumeNotAndroid() {
    assumeFalse(Platform.isAndroid)
  }

  fun assumeJdkVersion(majorVersion: Int) {
    assumeTrue(PlatformVersion.majorVersion == majorVersion)
  }

  companion object {
    const val PROPERTY_NAME = "okhttp.platform"
    const val CONSCRYPT_PROPERTY = "conscrypt"
    const val CORRETTO_PROPERTY = "corretto"
    const val JDK9_PROPERTY = "jdk9"
    const val JDK8_ALPN_PROPERTY = "jdk8alpn"
    const val JDK8_PROPERTY = "jdk8"
    const val OPENJSSE_PROPERTY = "openjsse"
    const val BOUNCYCASTLE_PROPERTY = "bouncycastle"

    init {
      val platformSystemProperty = getPlatformSystemProperty()

      if (platformSystemProperty == JDK9_PROPERTY) {
        if (System.getProperty("javax.net.debug") == null) {
          System.setProperty("javax.net.debug", "")
        }
      } else if (platformSystemProperty == CONSCRYPT_PROPERTY) {
        if (Security.getProviders()[0].name != "Conscrypt") {
          if (!Conscrypt.isAvailable()) {
            System.err.println("Warning: Conscrypt not available")
          }

          val provider = Conscrypt.newProviderBuilder()
              .provideTrustManager(true)
              .build()
          Security.insertProviderAt(provider, 1)
        }
      } else if (platformSystemProperty == JDK8_ALPN_PROPERTY) {
        if (!isAlpnBootEnabled()) {
          System.err.println("Warning: ALPN Boot not enabled")
        }
      } else if (platformSystemProperty == JDK8_PROPERTY) {
        if (isAlpnBootEnabled()) {
          System.err.println("Warning: ALPN Boot enabled unintentionally")
        }
      } else if (platformSystemProperty == OPENJSSE_PROPERTY && Security.getProviders()[0].name != "OpenJSSE") {
        if (!OpenJSSEPlatform.isSupported) {
          System.err.println("Warning: OpenJSSE not available")
        }

        if (System.getProperty("javax.net.debug") == null) {
          System.setProperty("javax.net.debug", "")
        }

        Security.insertProviderAt(OpenJSSE(), 1)
      } else if (platformSystemProperty == BOUNCYCASTLE_PROPERTY && Security.getProviders()[0].name != "BC") {
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
      } else if (platformSystemProperty == CORRETTO_PROPERTY) {
        AmazonCorrettoCryptoProvider.install()

        AmazonCorrettoCryptoProvider.INSTANCE.assertHealthy()
      }

      Platform.resetForTests()

      System.err.println("Running Tests with ${Platform.get().javaClass.simpleName}")
    }

    @JvmStatic
    fun getPlatformSystemProperty(): String {
      var property: String? = System.getProperty(PROPERTY_NAME)

      if (property == null) {
        property = when (Platform.get()) {
          is ConscryptPlatform -> CONSCRYPT_PROPERTY
          is OpenJSSEPlatform -> OPENJSSE_PROPERTY
          is Jdk8WithJettyBootPlatform -> CONSCRYPT_PROPERTY
          is Jdk9Platform -> {
            if (isCorrettoInstalled) CORRETTO_PROPERTY else JDK9_PROPERTY
          }
          else -> JDK8_PROPERTY
        }
      }

      return property
    }

    @JvmStatic
    fun conscrypt() = PlatformRule(CONSCRYPT_PROPERTY)

    @JvmStatic
    fun openjsse() = PlatformRule(OPENJSSE_PROPERTY)

    @JvmStatic
    fun jdk9() = PlatformRule(JDK9_PROPERTY)

    @JvmStatic
    fun jdk8() = PlatformRule(JDK8_PROPERTY)

    @JvmStatic
    fun jdk8alpn() = PlatformRule(JDK8_ALPN_PROPERTY)

    @JvmStatic
    fun bouncycastle() = PlatformRule(BOUNCYCASTLE_PROPERTY)

    @JvmStatic
    fun isAlpnBootEnabled(): Boolean = try {
      Class.forName("org.eclipse.jetty.alpn.ALPN", true, null)
      true
    } catch (cnfe: ClassNotFoundException) {
      false
    }

    val isCorrettoSupported: Boolean = try {
      // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
      Class.forName("com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider")

      AmazonCorrettoCryptoProvider.INSTANCE.loadingError == null &&
          AmazonCorrettoCryptoProvider.INSTANCE.runSelfTests() == SelfTestStatus.PASSED
    } catch (e: ClassNotFoundException) {
      false
    }

    val isCorrettoInstalled: Boolean =
      isCorrettoSupported && Security.getProviders()
          .first().name == AmazonCorrettoCryptoProvider.PROVIDER_NAME
  }
}
