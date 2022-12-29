/*
 * Copyright (C) 2012 Square, Inc.
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
import assertk.assertions.isNotEqualTo
import kotlin.test.Test
import kotlin.test.fail
import okhttp3.Headers.Companion.headersOf
import okhttp3.Headers.Companion.toHeaders

class HeadersTest {
  @Test fun ofTrims() {
    val headers = headersOf("\t User-Agent \n", " \r OkHttp ")
    assertThat(headers.name(0)).isEqualTo("User-Agent")
    assertThat(headers.value(0)).isEqualTo("OkHttp")
  }

  @Test fun ofThrowsOddNumberOfHeaders() {
    try {
      headersOf("User-Agent", "OkHttp", "Content-Length")
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofThrowsOnEmptyName() {
    try {
      headersOf("", "OkHttp")
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofAcceptsEmptyValue() {
    val headers = headersOf("User-Agent", "")
    assertThat(headers.value(0)).isEqualTo("")
  }

  @Test fun ofMakesDefensiveCopy() {
    val namesAndValues = arrayOf(
      "User-Agent",
      "OkHttp"
    )
    val headers = headersOf(*namesAndValues)
    namesAndValues[1] = "Chrome"
    assertThat(headers.value(0)).isEqualTo("OkHttp")
  }

  @Test fun ofRejectsNullChar() {
    try {
      headersOf("User-Agent", "Square\u0000OkHttp")
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapThrowsOnEmptyName() {
    try {
      mapOf("" to "OkHttp").toHeaders()
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapThrowsOnBlankName() {
    try {
      mapOf(" " to "OkHttp").toHeaders()
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapAcceptsEmptyValue() {
    val headers = mapOf("User-Agent" to "").toHeaders()
    assertThat(headers.value(0)).isEqualTo("")
  }

  @Test fun ofMapTrimsKey() {
    val headers = mapOf(" User-Agent " to "OkHttp").toHeaders()
    assertThat(headers.name(0)).isEqualTo("User-Agent")
  }

  @Test fun ofMapTrimsValue() {
    val headers = mapOf("User-Agent" to " OkHttp ").toHeaders()
    assertThat(headers.value(0)).isEqualTo("OkHttp")
  }

  @Test fun ofMapMakesDefensiveCopy() {
    val namesAndValues = mutableMapOf<String, String>()
    namesAndValues["User-Agent"] = "OkHttp"
    val headers = namesAndValues.toHeaders()
    namesAndValues["User-Agent"] = "Chrome"
    assertThat(headers.value(0)).isEqualTo("OkHttp")
  }

  @Test fun ofMapRejectsNullCharInName() {
    try {
      mapOf("User-\u0000Agent" to "OkHttp").toHeaders()
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapRejectsNullCharInValue() {
    try {
      mapOf("User-Agent" to "Square\u0000OkHttp").toHeaders()
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun builderRejectsUnicodeInHeaderName() {
    try {
      Headers.Builder().add("héader1", "value1")
      fail("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun builderRejectsUnicodeInHeaderValue() {
    try {
      Headers.Builder().add("header1", "valué1")
      fail("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun varargFactoryRejectsUnicodeInHeaderName() {
    try {
      headersOf("héader1", "value1")
      fail("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun varargFactoryRejectsUnicodeInHeaderValue() {
    try {
      headersOf("header1", "valué1")
      fail("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun mapFactoryRejectsUnicodeInHeaderName() {
    try {
      mapOf("héader1" to "value1").toHeaders()
      fail("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun mapFactoryRejectsUnicodeInHeaderValue() {
    try {
      mapOf("header1" to "valué1").toHeaders()
      fail("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun sensitiveHeadersNotIncludedInExceptions() {
    try {
      Headers.Builder().add("Authorization", "valué1")
      fail("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Authorization value")
    }
    try {
      Headers.Builder().add("Cookie", "valué1")
      fail("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Cookie value")
    }
    try {
      Headers.Builder().add("Proxy-Authorization", "valué1")
      fail("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Proxy-Authorization value")
    }
    try {
      Headers.Builder().add("Set-Cookie", "valué1")
      fail("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Set-Cookie value")
    }
  }

  @Test fun headersEquals() {
    val headers1 = Headers.Builder()
      .add("Connection", "close")
      .add("Transfer-Encoding", "chunked")
      .build()
    val headers2 = Headers.Builder()
      .add("Connection", "close")
      .add("Transfer-Encoding", "chunked")
      .build()
    assertThat(headers2).isEqualTo(headers1)
    assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode())
  }

  @Test fun headersNotEquals() {
    val headers1 = Headers.Builder()
      .add("Connection", "close")
      .add("Transfer-Encoding", "chunked")
      .build()
    val headers2 = Headers.Builder()
      .add("Connection", "keep-alive")
      .add("Transfer-Encoding", "chunked")
      .build()
    assertThat(headers2).isNotEqualTo(headers1)
    assertThat(headers2.hashCode()).isNotEqualTo(headers1.hashCode().toLong())
  }

  @Test fun headersToString() {
    val headers = Headers.Builder()
      .add("A", "a")
      .add("B", "bb")
      .build()
    assertThat(headers.toString()).isEqualTo("A: a\nB: bb\n")
  }

  @Test fun headersToStringRedactsSensitiveHeaders() {
    val headers = Headers.Builder()
      .add("content-length", "99")
      .add("authorization", "peanutbutter")
      .add("proxy-authorization", "chocolate")
      .add("cookie", "drink=coffee")
      .add("set-cookie", "accessory=sugar")
      .add("user-agent", "OkHttp")
      .build()
    assertThat(headers.toString()).isEqualTo(
      """
      |content-length: 99
      |authorization: ██
      |proxy-authorization: ██
      |cookie: ██
      |set-cookie: ██
      |user-agent: OkHttp
      |""".trimMargin()
    )
  }

  @Test fun headersAddAll() {
    val sourceHeaders = Headers.Builder()
      .add("A", "aa")
      .add("a", "aa")
      .add("B", "bb")
      .build()
    val headers = Headers.Builder()
      .add("A", "a")
      .addAll(sourceHeaders)
      .add("C", "c")
      .build()
    assertThat(headers.toString()).isEqualTo("A: a\nA: aa\na: aa\nB: bb\nC: c\n")
  }

  @Test fun nameIndexesAreStrict() {
    val headers = headersOf("a", "b", "c", "d")
    try {
      headers.name(-1)
      fail()
    } catch (expected: IndexOutOfBoundsException) {
    }
    assertThat(headers.name(0)).isEqualTo("a")
    assertThat(headers.name(1)).isEqualTo("c")
    try {
      headers.name(2)
      fail()
    } catch (expected: IndexOutOfBoundsException) {
    }
  }

  @Test fun valueIndexesAreStrict() {
    val headers = headersOf("a", "b", "c", "d")
    try {
      headers.value(-1)
      fail()
    } catch (expected: IndexOutOfBoundsException) {
    }
    assertThat(headers.value(0)).isEqualTo("b")
    assertThat(headers.value(1)).isEqualTo("d")
    try {
      headers.value(2)
      fail()
    } catch (expected: IndexOutOfBoundsException) {
    }
  }
}
