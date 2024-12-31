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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import java.time.Instant
import java.util.Date
import kotlin.test.assertFailsWith
import okhttp3.Headers.Companion.toHeaders
import okhttp3.internal.EMPTY_HEADERS
import org.junit.jupiter.api.Test

class HeadersJvmTest {
  @Test fun byteCount() {
    assertThat(EMPTY_HEADERS.byteCount()).isEqualTo(0L)
    assertThat(
      Headers.Builder()
        .add("abc", "def")
        .build()
        .byteCount(),
    ).isEqualTo(10L)
    assertThat(
      Headers.Builder()
        .add("abc", "def")
        .add("ghi", "jkl")
        .build()
        .byteCount(),
    ).isEqualTo(20L)
  }

  @Test fun addDate() {
    val expected = Date(0L)
    val headers =
      Headers.Builder()
        .add("testDate", expected)
        .build()
    assertThat(headers["testDate"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(headers.getDate("testDate")).isEqualTo(Date(0L))
  }

  @Test fun addInstant() {
    val expected = Instant.ofEpochMilli(0L)
    val headers =
      Headers.Builder()
        .add("Test-Instant", expected)
        .build()
    assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
  }

  @Test fun setDate() {
    val expected = Date(1000)
    val headers =
      Headers.Builder()
        .add("testDate", Date(0L))
        .set("testDate", expected)
        .build()
    assertThat(headers["testDate"]).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT")
    assertThat(headers.getDate("testDate")).isEqualTo(expected)
  }

  @Test fun setInstant() {
    val expected = Instant.ofEpochMilli(1000L)
    val headers =
      Headers.Builder()
        .add("Test-Instant", Instant.ofEpochMilli(0L))
        .set("Test-Instant", expected)
        .build()
    assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT")
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
  }

  @Test fun addParsing() {
    val headers =
      Headers.Builder()
        .add("foo: bar")
        .add(" foo: baz") // Name leading whitespace is trimmed.
        .add("foo : bak") // Name trailing whitespace is trimmed.
        .add("\tkey\t:\tvalue\t") // '\t' also counts as whitespace
        .add("ping:  pong  ") // Value whitespace is trimmed.
        .add("kit:kat") // Space after colon is not required.
        .build()
    assertThat(headers.values("foo")).containsExactly("bar", "baz", "bak")
    assertThat(headers.values("key")).containsExactly("value")
    assertThat(headers.values("ping")).containsExactly("pong")
    assertThat(headers.values("kit")).containsExactly("kat")
  }

  @Test fun addThrowsOnEmptyName() {
    assertFailsWith<IllegalArgumentException> {
      Headers.Builder().add(": bar")
    }
    assertFailsWith<IllegalArgumentException> {
      Headers.Builder().add(" : bar")
    }
  }

  @Test fun addThrowsOnNoColon() {
    assertFailsWith<IllegalArgumentException> {
      Headers.Builder().add("foo bar")
    }
  }

  @Test fun addThrowsOnMultiColon() {
    assertFailsWith<IllegalArgumentException> {
      Headers.Builder().add(":status: 200 OK")
    }
  }

  @Test fun addUnsafeNonAsciiRejectsUnicodeName() {
    assertFailsWith<IllegalArgumentException> {
      Headers.Builder()
        .addUnsafeNonAscii("héader1", "value1")
        .build()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun addUnsafeNonAsciiAcceptsUnicodeValue() {
    val headers =
      Headers.Builder()
        .addUnsafeNonAscii("header1", "valué1")
        .build()
    assertThat(headers.toString()).isEqualTo("header1: valué1\n")
  }

  // Fails on JS, ClassCastException: Illegal cast
  @Test fun ofMapThrowsOnNull() {
    assertFailsWith<NullPointerException> {
      (mapOf("User-Agent" to null) as Map<String, String>).toHeaders()
    }
  }

  @Test fun toMultimapGroupsHeaders() {
    val headers =
      Headers.headersOf(
        "cache-control",
        "no-cache",
        "cache-control",
        "no-store",
        "user-agent",
        "OkHttp",
      )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
  }

  @Test fun toMultimapUsesCanonicalCase() {
    val headers =
      Headers.headersOf(
        "cache-control",
        "no-store",
        "Cache-Control",
        "no-cache",
        "User-Agent",
        "OkHttp",
      )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
  }

  @Test fun toMultimapAllowsCaseInsensitiveGet() {
    val headers =
      Headers.headersOf(
        "cache-control",
        "no-store",
        "Cache-Control",
        "no-cache",
      )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["Cache-Control"]!!.size).isEqualTo(2)
  }
}
