/*
 * Copyright (c) 2025 Block, Inc.
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
 *
 */
package okhttp.android.test

import assertk.all
import assertk.assertFailure
import assertk.assertions.cause
import assertk.assertions.hasClass
import assertk.assertions.hasMessage
import assertk.assertions.isNotNull
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.PlatformRegistry
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [23, 26, 30, 33, 35],
)
class DisabledInitialiserTest {
  @Before
  fun setContext() {
    // Ensure we aren't succeeding because of another test
    Platform.resetForTests()
    PlatformRegistry.applicationContext = null
  }

  @Test
  fun testWithoutContext() {
    val httpUrl = "https://www.google.co.uk".toHttpUrl()
    assertFailure { httpUrl.topPrivateDomain() }.all {
      hasMessage("Unable to load PublicSuffixDatabase.list resource.")
      cause().isNotNull().all {
        hasMessage(
          "Platform applicationContext not initialized. " +
            "Startup Initializer possibly disabled, " +
            "call OkHttp.initialize before test.",
        )
        hasClass<IOException>()
      }
    }
  }

  companion object {
    @AfterClass
    @JvmStatic
    fun resetContext() {
      // Ensure we don't make other tests fail
      Platform.resetForTests()
    }
  }
}
