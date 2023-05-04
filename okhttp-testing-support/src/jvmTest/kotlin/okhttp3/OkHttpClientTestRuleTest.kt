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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail

class OkHttpClientTestRuleTest {
  lateinit var extensionContext: ExtensionContext

  @RegisterExtension @JvmField val beforeEachCallback = BeforeEachCallback { context ->
    this@OkHttpClientTestRuleTest.extensionContext = context
  }

  @Test fun uncaughtException() {
    val testRule = OkHttpClientTestRule()
    testRule.beforeEach(extensionContext)

    val thread = object : Thread() {
      override fun run() {
        throw RuntimeException("boom!")
      }
    }
    thread.start()
    thread.join()

    try {
      testRule.afterEach(extensionContext)
      fail("")
    } catch (expected: AssertionError) {
      assertThat(expected).hasMessage("uncaught exception thrown during test")
      assertThat(expected.cause).hasMessage("boom!")
    }
  }
}
