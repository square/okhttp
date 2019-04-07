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

import okhttp3.internal.platform.Platform
import org.conscrypt.Conscrypt
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.Assume.assumeThat
import org.junit.rules.ExternalResource
import java.security.Security

/**
 * Marks a test as Platform aware, before the test runs a consistent Platform will be
 * established e.g. SecurityManager for Conscrypt installed.
 *
 * Also allows a test file to state general platform assumptions, or for individual test.
 */
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
    if (platform != null) {
      Platform.reset()
    }
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