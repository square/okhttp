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
package okhttp3

import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Jdk8WithJettyBootPlatform
import okhttp3.internal.platform.Jdk9Platform
import okhttp3.internal.platform.Platform
import org.conscrypt.Conscrypt
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.Assume.assumeThat
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
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
) : ExternalResource() {
  override fun before() {
    if (requiredPlatformName != null) {
      assumeThat(getPlatformSystemProperty(), equalTo(requiredPlatformName))
    }

    if (platform != null) {
      Platform.resetForTests(platform)
    } else {
      Platform.resetForTests()
    }
  }

  override fun after() {
    if (platform != null) {
      Platform.resetForTests()
    }
  }

  fun isConscrypt() = getPlatformSystemProperty() == CONSCRYPT_PROPERTY

  fun isJdk9() = getPlatformSystemProperty() == JDK9_PROPERTY

  fun isJdk8() = getPlatformSystemProperty() == JDK8_PROPERTY

  fun isJdk8Alpn() = getPlatformSystemProperty() == JDK8_ALPN_PROPERTY

  fun hasHttp2Support() = !isJdk8()

  fun assumeConscrypt() {
    assumeThat(getPlatformSystemProperty(), equalTo(
        CONSCRYPT_PROPERTY))
  }

  fun assumeJdk9() {
    assumeThat(getPlatformSystemProperty(), equalTo(
        JDK9_PROPERTY))
  }

  fun assumeJdk8() {
    assumeThat(getPlatformSystemProperty(), equalTo(
        JDK8_PROPERTY))
  }

  fun assumeJdk8Alpn() {
    assumeThat(getPlatformSystemProperty(), equalTo(
        JDK8_ALPN_PROPERTY))
  }

  fun assumeHttp2Support() {
    assumeThat(getPlatformSystemProperty(), not(
        JDK8_PROPERTY))
  }

  fun assumeNotConscrypt() {
    assumeThat(getPlatformSystemProperty(), not(
        CONSCRYPT_PROPERTY))
  }

  fun assumeNotJdk9() {
    assumeThat(getPlatformSystemProperty(), not(
        JDK9_PROPERTY))
  }

  fun assumeNotJdk8() {
    assumeThat(getPlatformSystemProperty(), not(
        JDK8_PROPERTY))
  }

  fun assumeNotJdk8Alpn() {
    assumeThat(getPlatformSystemProperty(), not(
        JDK8_ALPN_PROPERTY))
  }

  fun assumeNotHttp2Support() {
    assumeThat(getPlatformSystemProperty(), equalTo(
        JDK8_PROPERTY))
  }

  fun assumeJettyBootEnabled() {
    assumeTrue("ALPN Boot not enabled", isAlpnBootEnabled())
  }

  companion object {
    const val PROPERTY_NAME = "okhttp.platform"
    const val CONSCRYPT_PROPERTY = "conscrypt"
    const val JDK9_PROPERTY = "jdk9"
    const val JDK8_ALPN_PROPERTY = "jdk8alpn"
    const val JDK8_PROPERTY = "jdk8"

    init {
      if (getPlatformSystemProperty() == CONSCRYPT_PROPERTY && Security.getProviders()[0].name != "Conscrypt") {
        if (!Conscrypt.isAvailable()) {
          System.err.println("Warning: Conscrypt not available")
        }

        val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
        Security.insertProviderAt(provider, 1)
      } else if (getPlatformSystemProperty() == JDK8_ALPN_PROPERTY) {
        if (!isAlpnBootEnabled()) {
          System.err.println("Warning: ALPN Boot not enabled")
        }
      } else if (getPlatformSystemProperty() == JDK8_PROPERTY) {
        if (isAlpnBootEnabled()) {
          System.err.println("Warning: ALPN Boot enabled unintentionally")
        }
      }
    }

    @JvmStatic
    fun getPlatformSystemProperty(): String {
      var property: String? = System.getProperty(PROPERTY_NAME)

      if (property == null) {
        property = when (Platform.get()) {
          is ConscryptPlatform -> CONSCRYPT_PROPERTY
          is Jdk8WithJettyBootPlatform -> CONSCRYPT_PROPERTY
          is Jdk9Platform -> JDK9_PROPERTY
          else -> JDK8_PROPERTY
        }
      }

      return property
    }

    @JvmStatic
    fun conscrypt() = PlatformRule(CONSCRYPT_PROPERTY)

    @JvmStatic
    fun jdk9() = PlatformRule(JDK9_PROPERTY)

    @JvmStatic
    fun jdk8() = PlatformRule(JDK8_PROPERTY)

    @JvmStatic
    fun jdk8alpn() = PlatformRule(JDK8_ALPN_PROPERTY)

    @JvmStatic
    fun isAlpnBootEnabled(): Boolean = try {
      Class.forName("org.eclipse.jetty.alpn.ALPN", true, null)
      true
    } catch (cnfe: ClassNotFoundException) {
      false
    }
  }
}