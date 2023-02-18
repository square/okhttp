/*
 * Copyright (C) 2023 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs the web platform ToAscii tests. */
class WebPlatformToAsciiTest {
  val knownFailures = setOf(
    // OkHttp rejects empty labels.
    "x..xn--zca",
    "x..ß",

    // OkHttp rejects labels longer than 63 code points, the web platform tests don't.
    "x01234567890123456789012345678901234567890123456789012345678901x.xn--zca",
    "x01234567890123456789012345678901234567890123456789012345678901x.ß",
    "x01234567890123456789012345678901234567890123456789012345678901x",
    "x01234567890123456789012345678901234567890123456789012345678901†",

    // OkHttp rejects domain names longer than 253 code points, the web platform tests don't.
    "01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.0123456789012345678901234567890123456789012345678.x",
    "01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.0123456789012345678901234567890123456789012345678.xn--zca",
    "01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.01234567890123456789012345678901234567890123456789.0123456789012345678901234567890123456789012345678.ß",

    // OkHttp incorrectly does transitional processing, so it maps 'ß' to 'ss'
    "-x.ß",
    "ab--c.ß",
    "x-.ß",
    "xn--a.ß",
    "xn--zca.ß",
    "ශ්‍රී",

    // OkHttp does not reject invalid Punycode.
    "xn--",
    "xn--a",
    "xn--a.xn--zca",
    "xn--a-yoc",
    "xn--ls8h=",

    // OkHttp doesn't reject U+FFFD encoded in Punycode.
    "xn--zn7c.com",

    // OkHttp doesn't reject a U+200D. https://www.rfc-editor.org/rfc/rfc5892.html#appendix-A.2
    "xn--1ug.example",

    // OkHttp returns `xn--mgba3gch31f`, not `xn--mgba3gch31f060k`.
    "نامه‌ای",
  )

  @TestFactory
  fun testFactory(): List<DynamicTest> {
    val list = WebPlatformToAsciiData.load()
    return list.map { entry ->
      DynamicTest.dynamicTest(entry.input!!) {
        var failure: AssertionError? = null
        try {
          testToAscii(entry.input!!, entry.output, entry.comment)
        } catch (e: AssertionError) {
          failure = e
        }

        if (entry.input in knownFailures) {
          assertThat(failure).isNotNull()
        } else {
          if (failure != null) throw failure
        }
      }
    }
  }

  private fun testToAscii(input: String, output: String?, comment: String?) {
    val url = "https://$input/".toHttpUrlOrNull()
    assertThat(url?.host, name = comment ?: input).isEqualTo(output)
  }
}
