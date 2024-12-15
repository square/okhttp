/*
 * Copyright (C) 2023 Block, Inc.
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
import kotlin.test.Test
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Runs the web platform ToAscii tests. */
class WebPlatformToAsciiTest {
  @Suppress("ktlint:standard:max-line-length")
  val knownFailures =
    setOf(
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
      // OkHttp does not reject invalid Punycode.
      "xn--a",
      "xn--a.ß",
      "xn--a.xn--zca",
      "xn--a-yoc",
      // OkHttp doesn't reject U+FFFD encoded in Punycode.
      "xn--zn7c.com",
      // OkHttp doesn't reject a U+200D. https://www.rfc-editor.org/rfc/rfc5892.html#appendix-A.2
      "xn--1ug.example",
      // OkHttp doesn't implement CheckJoiners.
      "\u200D.example",
      // OkHttp doesn't implement CheckBidi.
      "يa",
    )

  @Test
  fun test() {
    val list = WebPlatformToAsciiData.load()
    val failures = mutableListOf<Throwable>()
    for (entry in list) {
      var failure: Throwable? = null
      try {
        testToAscii(entry.input!!, entry.output, entry.comment)
      } catch (e: Throwable) {
        failure = e
      }

      if (entry.input in knownFailures) {
        if (failure == null) failures += AssertionError("known failure didn't fail: $entry")
      } else {
        if (failure != null) failures += failure
      }
    }

    if (failures.isNotEmpty()) {
      for (failure in failures) {
        println(failure)
      }
      throw failures.first()
    }
  }

  private fun testToAscii(
    input: String,
    output: String?,
    comment: String?,
  ) {
    val url = "https://$input/".toHttpUrlOrNull()
    assertThat(url?.host, name = comment ?: input).isEqualTo(output)
  }
}
