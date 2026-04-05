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
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Android test running with only stubs.
 */
@RunWith(JUnit4::class)
class NonRobolectricOkHttpClientTest : BaseOkHttpClientUnitTest() {
  @Test
  override fun testPublicSuffixDb() {
    assertFailure { super.testPublicSuffixDb() }.all {
      hasMessage("Unable to load PublicSuffixDatabase.list resource.")
      cause().isNotNull().all {
        hasMessage(
          "Platform applicationContext not initialized. " +
            "Possibly running Android unit test without Robolectric. " +
            "Android tests should run with Robolectric " +
            "and call OkHttp.initialize before test",
        )
        hasClass<IOException>()
      }
    }
  }
}
