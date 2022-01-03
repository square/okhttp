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

import java.time.Instant
import java.util.Date
import okhttp3.Headers.Companion.headersOf
import okhttp3.Headers.Companion.toHeaders
import okhttp3.TestUtil.headerEntries
import okhttp3.internal.EMPTY_HEADERS
import okhttp3.internal.http.parseChallenges
import okhttp3.internal.http2.Http2ExchangeCodec.Companion.http2HeadersList
import okhttp3.internal.http2.Http2ExchangeCodec.Companion.readHttp2HeadersList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class HeadersTest {
  @Test fun readNameValueBlockDropsForbiddenHeadersHttp2() {
    val headerBlock = headersOf(
      ":status", "200 OK",
      ":version", "HTTP/1.1",
      "connection", "close"
    )
    val request = Request.Builder().url("http://square.com/").build()
    val response = readHttp2HeadersList(headerBlock, Protocol.HTTP_2).request(request).build()
    val headers = response.headers
    assertThat(headers.size).isEqualTo(1)
    assertThat(headers.name(0)).isEqualTo(":version")
    assertThat(headers.value(0)).isEqualTo("HTTP/1.1")
  }

  @Test fun http2HeadersListDropsForbiddenHeadersHttp2() {
    val request = Request.Builder()
      .url("http://square.com/")
      .header("Connection", "upgrade")
      .header("Upgrade", "websocket")
      .header("Host", "square.com")
      .header("TE", "gzip")
      .build()
    val expected = headerEntries(
      ":method", "GET",
      ":path", "/",
      ":authority", "square.com",
      ":scheme", "http"
    )
    assertThat(http2HeadersList(request)).isEqualTo(expected)
  }

  @Test fun http2HeadersListDontDropTeIfTrailersHttp2() {
    val request = Request.Builder()
      .url("http://square.com/")
      .header("TE", "trailers")
      .build()
    val expected = headerEntries(
      ":method", "GET",
      ":path", "/",
      ":scheme", "http",
      "te", "trailers"
    )
    assertThat(http2HeadersList(request)).isEqualTo(expected)
  }

  @Test fun ofTrims() {
    val headers = headersOf("\t User-Agent \n", " \r OkHttp ")
    assertThat(headers.name(0)).isEqualTo("User-Agent")
    assertThat(headers.value(0)).isEqualTo("OkHttp")
  }

  @Test fun addParsing() {
    val headers = Headers.Builder()
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
    try {
      Headers.Builder().add(": bar")
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
    try {
      Headers.Builder().add(" : bar")
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun addThrowsOnNoColon() {
    try {
      Headers.Builder().add("foo bar")
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun addThrowsOnMultiColon() {
    try {
      Headers.Builder().add(":status: 200 OK")
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun addUnsafeNonAsciiRejectsUnicodeName() {
    try {
      Headers.Builder()
        .addUnsafeNonAscii("héader1", "value1")
        .build()
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun addUnsafeNonAsciiAcceptsUnicodeValue() {
    val headers = Headers.Builder()
      .addUnsafeNonAscii("header1", "valué1")
      .build()
    assertThat(headers.toString()).isEqualTo("header1: valué1\n")
  }

  @Test fun ofThrowsOddNumberOfHeaders() {
    try {
      headersOf("User-Agent", "OkHttp", "Content-Length")
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofThrowsOnEmptyName() {
    try {
      headersOf("", "OkHttp")
      fail<Any>()
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
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapThrowsOnNull() {
    try {
      (mapOf("User-Agent" to null) as Map<String, String>).toHeaders()
      fail<Any>()
    } catch (expected: NullPointerException) {
    }
  }

  @Test fun ofMapThrowsOnEmptyName() {
    try {
      mapOf("" to "OkHttp").toHeaders()
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapThrowsOnBlankName() {
    try {
      mapOf(" " to "OkHttp").toHeaders()
      fail<Any>()
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
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun ofMapRejectsNullCharInValue() {
    try {
      mapOf("User-Agent" to "Square\u0000OkHttp").toHeaders()
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun toMultimapGroupsHeaders() {
    val headers = headersOf(
      "cache-control", "no-cache",
      "cache-control", "no-store",
      "user-agent", "OkHttp"
    )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
  }

  @Test fun toMultimapUsesCanonicalCase() {
    val headers = headersOf(
      "cache-control", "no-store",
      "Cache-Control", "no-cache",
      "User-Agent", "OkHttp"
    )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
  }

  @Test fun toMultimapAllowsCaseInsensitiveGet() {
    val headers = headersOf(
      "cache-control", "no-store",
      "Cache-Control", "no-cache"
    )
    val headerMap = headers.toMultimap()
    assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
    assertThat(headerMap["Cache-Control"]!!.size).isEqualTo(2)
  }

  @Test fun nameIndexesAreStrict() {
    val headers = headersOf("a", "b", "c", "d")
    try {
      headers.name(-1)
      fail<Any>()
    } catch (expected: IndexOutOfBoundsException) {
    }
    assertThat(headers.name(0)).isEqualTo("a")
    assertThat(headers.name(1)).isEqualTo("c")
    try {
      headers.name(2)
      fail<Any>()
    } catch (expected: IndexOutOfBoundsException) {
    }
  }

  @Test fun valueIndexesAreStrict() {
    val headers = headersOf("a", "b", "c", "d")
    try {
      headers.value(-1)
      fail<Any>()
    } catch (expected: IndexOutOfBoundsException) {
    }
    assertThat(headers.value(0)).isEqualTo("b")
    assertThat(headers.value(1)).isEqualTo("d")
    try {
      headers.value(2)
      fail<Any>()
    } catch (expected: IndexOutOfBoundsException) {
    }
  }

  @Test fun builderRejectsUnicodeInHeaderName() {
    try {
      Headers.Builder().add("héader1", "value1")
      fail<Any>("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun builderRejectsUnicodeInHeaderValue() {
    try {
      Headers.Builder().add("header1", "valué1")
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun varargFactoryRejectsUnicodeInHeaderName() {
    try {
      headersOf("héader1", "value1")
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun varargFactoryRejectsUnicodeInHeaderValue() {
    try {
      headersOf("header1", "valué1")
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun mapFactoryRejectsUnicodeInHeaderName() {
    try {
      mapOf("héader1" to "value1").toHeaders()
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
    }
  }

  @Test fun mapFactoryRejectsUnicodeInHeaderValue() {
    try {
      mapOf("header1" to "valué1").toHeaders()
      fail<Any>("Should have complained about invalid value")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in header1 value: valué1")
    }
  }

  @Test fun sensitiveHeadersNotIncludedInExceptions() {
    try {
      Headers.Builder().add("Authorization", "valué1")
      fail<Any>("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Authorization value")
    }
    try {
      Headers.Builder().add("Cookie", "valué1")
      fail<Any>("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Cookie value")
    }
    try {
      Headers.Builder().add("Proxy-Authorization", "valué1")
      fail<Any>("Should have complained about invalid name")
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("Unexpected char 0xe9 at 4 in Proxy-Authorization value")
    }
    try {
      Headers.Builder().add("Set-Cookie", "valué1")
      fail<Any>("Should have complained about invalid name")
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

  /** See https://github.com/square/okhttp/issues/2780.  */
  @Test fun testDigestChallengeWithStrictRfc2617Header() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
        + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithDifferentlyOrderedAuthParams() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest qop=\"auth\", realm=\"myrealm\", nonce=\"fjalskdflwejrlask"
        + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithDifferentlyOrderedAuthParams2() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest qop=\"auth\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaksjdflk"
        + "asdf\", realm=\"myrealm\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithMissingRealm() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest qop=\"auth\", underrealm=\"myrealm\", nonce=\"fjalskdflwej"
        + "rlaskdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isNull()
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["underrealm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithAdditionalSpaces() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest qop=\"auth\",    realm=\"myrealm\", nonce=\"fjalskdflwejrl"
        + "askdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithAdditionalSpacesBeforeFirstAuthParam() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "Digest    realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjfl"
        + "aksjdflkasdf\", qop=\"auth\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithCamelCasedNames() {
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "DiGeSt qop=\"auth\", rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlask"
        + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("DiGeSt")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithCamelCasedNames2() {
    // Strict RFC 2617 camelcased.
    val headers = Headers.Builder()
      .add(
        "WWW-Authenticate", "DIgEsT rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
        + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\""
      )
      .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("DIgEsT")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithTokenFormOfAuthParam() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest realm=myrealm").build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    assertThat(challenges[0].authParams)
      .isEqualTo(mapOf("realm" to "myrealm"))
  }

  @Test fun testDigestChallengeWithoutAuthParams() {
    // Scheme only.
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest").build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isNull()
    assertThat(challenges[0].authParams).isEqualTo(emptyMap<Any, Any>())
  }

  @Test fun basicChallenge() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate: Basic realm=\"protected area\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "protected area"))))
  }

  @Test fun basicChallengeWithCharset() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
      .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "protected area"
    expectedAuthParams["charset"] = "UTF-8"
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", expectedAuthParams)))
  }

  @Test fun basicChallengeWithUnexpectedCharset() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"US-ASCII\"")
      .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "protected area"
    expectedAuthParams["charset"] = "US-ASCII"
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", expectedAuthParams)))
  }

  @Test fun separatorsBeforeFirstChallenge() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", " ,  , Basic realm=myrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "myrealm"))))
  }

  @Test fun spacesAroundKeyValueSeparator() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Basic realm = \"myrealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "myrealm"))))
  }

  @Test fun multipleChallengesInOneHeader() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Basic realm = \"myrealm\",Digest")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Digest", mapOf())
    )
  }

  @Test fun multipleChallengesWithSameSchemeButDifferentRealmInOneHeader() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Basic realm = \"myrealm\",Basic realm=myotherrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Basic", mapOf("realm" to "myotherrealm"))
    )
  }

  @Test fun separatorsBeforeFirstAuthParam() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest, Basic ,,realm=\"myrealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm"))
    )
  }

  @Test fun onlyCommaBetweenChallenges() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,Basic realm=\"myrealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm"))
    )
  }

  @Test fun multipleSeparatorsBetweenChallenges() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,realm=\"myrealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm"))
    )
  }

  @Test fun unknownAuthParams() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,foo=bar,realm=\"myrealm\"")
      .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["foo"] = "bar"
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", expectedAuthParams)
    )
  }

  @Test fun escapedCharactersInQuotedString() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\"r\\ealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "my\\\"realm"))
    )
  }

  @Test fun commaInQuotedStringAndBeforeFirstChallenge() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", ",Digest,,,, Basic ,,,realm=\"my, realm,\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "my, realm,"))
    )
  }

  @Test fun unescapedDoubleQuoteInQuotedStringWithEvenNumberOfBackslashesInFront() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\\\"r\\ealm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf())
    )
  }

  @Test fun unescapedDoubleQuoteInQuotedString() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\"realm\"")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf())
    )
  }

  @Disabled("TODO(jwilson): reject parameters that use invalid characters")
  @Test fun doubleQuoteInToken() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=my\"realm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf())
    )
  }

  @Test fun token68InsteadOfAuthParams() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Other abc==")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Other", mapOf(null to "abc==")))
    )
  }

  @Test fun token68AndAuthParams() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Other abc==, realm=myrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Other", mapOf(null to "abc=="))
    )
  }

  @Test fun repeatedAuthParamKey() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Other realm=myotherrealm, realm=myrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).isEqualTo(listOf<Any>())
  }

  @Test fun multipleAuthenticateHeaders() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Digest")
      .add("WWW-Authenticate", "Basic realm=myrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm"))
    )
  }

  @Test fun multipleAuthenticateHeadersInDifferentOrder() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Basic realm=myrealm")
      .add("WWW-Authenticate", "Digest")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Digest", mapOf())
    )
  }

  @Test fun multipleBasicAuthenticateHeaders() {
    val headers = Headers.Builder()
      .add("WWW-Authenticate", "Basic realm=myrealm")
      .add("WWW-Authenticate", "Basic realm=myotherrealm")
      .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Basic", mapOf("realm" to "myotherrealm"))
    )
  }

  @Test fun byteCount() {
    assertThat(EMPTY_HEADERS.byteCount()).isEqualTo(0L)
    assertThat(
      Headers.Builder()
        .add("abc", "def")
        .build()
        .byteCount()
    ).isEqualTo(10L)
    assertThat(
      Headers.Builder()
        .add("abc", "def")
        .add("ghi", "jkl")
        .build()
        .byteCount()
    ).isEqualTo(20L)
  }

  @Test fun addDate() {
    val expected = Date(0L)
    val headers = Headers.Builder()
      .add("testDate", expected)
      .build()
    assertThat(headers["testDate"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(headers.getDate("testDate")).isEqualTo(Date(0L))
  }

  @Test fun addInstant() {
    val expected = Instant.ofEpochMilli(0L)
    val headers = Headers.Builder()
      .add("Test-Instant", expected)
      .build()
    assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
  }

  @Test fun setDate() {
    val expected = Date(1000)
    val headers = Headers.Builder()
      .add("testDate", Date(0L))
      .set("testDate", expected)
      .build()
    assertThat(headers["testDate"]).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT")
    assertThat(headers.getDate("testDate")).isEqualTo(expected)
  }

  @Test fun setInstant() {
    val expected = Instant.ofEpochMilli(1000L)
    val headers = Headers.Builder()
      .add("Test-Instant", Instant.ofEpochMilli(0L))
      .set("Test-Instant", expected)
      .build()
    assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT")
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
  }
}
