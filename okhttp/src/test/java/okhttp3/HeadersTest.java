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
package okhttp3;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.TypeCastException;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2ExchangeCodec;
import org.junit.Ignore;
import org.junit.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static okhttp3.TestUtil.headerEntries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class HeadersTest {
  @Test public void readNameValueBlockDropsForbiddenHeadersHttp2() {
    Headers headerBlock = Headers.of(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = Http2ExchangeCodec.Companion.readHttp2HeadersList(headerBlock, Protocol.HTTP_2).request(request).build();
    Headers headers = response.headers();
    assertThat(headers.size()).isEqualTo(1);
    assertThat(headers.name(0)).isEqualTo(":version");
    assertThat(headers.value(0)).isEqualTo("HTTP/1.1");
  }

  @Test public void http2HeadersListDropsForbiddenHeadersHttp2() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("Connection", "upgrade")
        .header("Upgrade", "websocket")
        .header("Host", "square.com")
        .header("TE", "gzip")
        .build();
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":authority", "square.com",
        ":scheme", "http");
    assertThat(Http2ExchangeCodec.Companion.http2HeadersList(request)).isEqualTo(expected);
  }

  @Test public void http2HeadersListDontDropTeIfTrailersHttp2() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("TE", "trailers")
        .build();
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":scheme", "http",
        "te", "trailers");
    assertThat(Http2ExchangeCodec.Companion.http2HeadersList(request)).isEqualTo(expected);
  }

  @Test public void ofTrims() {
    Headers headers = Headers.of("\t User-Agent \n", " \r OkHttp ");
    assertThat(headers.name(0)).isEqualTo("User-Agent");
    assertThat(headers.value(0)).isEqualTo("OkHttp");
  }

  @Test public void addParsing() {
    Headers headers = new Headers.Builder()
        .add("foo: bar")
        .add(" foo: baz") // Name leading whitespace is trimmed.
        .add("foo : bak") // Name trailing whitespace is trimmed.
        .add("\tkey\t:\tvalue\t") // '\t' also counts as whitespace
        .add("ping:  pong  ") // Value whitespace is trimmed.
        .add("kit:kat") // Space after colon is not required.
        .build();
    assertThat(headers.values("foo")).containsExactly("bar", "baz", "bak");
    assertThat(headers.values("key")).containsExactly("value");
    assertThat(headers.values("ping")).containsExactly("pong");
    assertThat(headers.values("kit")).containsExactly("kat");
  }

  @Test public void addThrowsOnEmptyName() {
    try {
      new Headers.Builder().add(": bar");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new Headers.Builder().add(" : bar");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void addThrowsOnNoColon() {
    try {
      new Headers.Builder().add("foo bar");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void addThrowsOnMultiColon() {
    try {
      new Headers.Builder().add(":status: 200 OK");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void addUnsafeNonAsciiRejectsUnicodeName() {
    try {
      new Headers.Builder()
          .addUnsafeNonAscii("héader1", "value1")
          .build();
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 1 in header name: héader1");
    }
  }

  @Test public void addUnsafeNonAsciiAcceptsUnicodeValue() {
    Headers headers = new Headers.Builder()
        .addUnsafeNonAscii("header1", "valué1")
        .build();
    assertThat(headers.toString()).isEqualTo("header1: valué1\n");
  }

  @Test public void ofThrowsOddNumberOfHeaders() {
    try {
      Headers.of("User-Agent", "OkHttp", "Content-Length");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofThrowsOnNull() {
    try {
      Headers.of("User-Agent", null);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofThrowsOnEmptyName() {
    try {
      Headers.of("", "OkHttp");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofAcceptsEmptyValue() {
    Headers headers = Headers.of("User-Agent", "");
    assertThat(headers.value(0)).isEqualTo("");
  }

  @Test public void ofMakesDefensiveCopy() {
    String[] namesAndValues = {
        "User-Agent",
        "OkHttp"
    };
    Headers headers = Headers.of(namesAndValues);
    namesAndValues[1] = "Chrome";
    assertThat(headers.value(0)).isEqualTo("OkHttp");
  }

  @Test public void ofRejectsNullChar() {
    try {
      Headers.of("User-Agent", "Square\u0000OkHttp");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapThrowsOnNull() {
    try {
      Headers.of(Collections.singletonMap("User-Agent", null));
      fail();
    } catch (TypeCastException expected) {
    }
  }

  @Test public void ofMapThrowsOnEmptyName() {
    try {
      Headers.of(singletonMap("", "OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapThrowsOnBlankName() {
    try {
      Headers.of(singletonMap(" ", "OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapAcceptsEmptyValue() {
    Headers headers = Headers.of(singletonMap("User-Agent", ""));
    assertThat(headers.value(0)).isEqualTo("");
  }

  @Test public void ofMapTrimsKey() {
    Headers headers = Headers.of(singletonMap(" User-Agent ", "OkHttp"));
    assertThat(headers.name(0)).isEqualTo("User-Agent");
  }

  @Test public void ofMapTrimsValue() {
    Headers headers = Headers.of(singletonMap("User-Agent", " OkHttp "));
    assertThat(headers.value(0)).isEqualTo("OkHttp");
  }

  @Test public void ofMapMakesDefensiveCopy() {
    Map<String, String> namesAndValues = new LinkedHashMap<>();
    namesAndValues.put("User-Agent", "OkHttp");

    Headers headers = Headers.of(namesAndValues);
    namesAndValues.put("User-Agent", "Chrome");
    assertThat(headers.value(0)).isEqualTo("OkHttp");
  }

  @Test public void ofMapRejectsNullCharInName() {
    try {
      Headers.of(singletonMap("User-\u0000Agent", "OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapRejectsNullCharInValue() {
    try {
      Headers.of(singletonMap("User-Agent", "Square\u0000OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void toMultimapGroupsHeaders() {
    Headers headers = Headers.of(
        "cache-control", "no-cache",
        "cache-control", "no-store",
        "user-agent", "OkHttp");
    Map<String, List<String>> headerMap = headers.toMultimap();
    assertThat(headerMap.get("cache-control").size()).isEqualTo(2);
    assertThat(headerMap.get("user-agent").size()).isEqualTo(1);
  }

  @Test public void toMultimapUsesCanonicalCase() {
    Headers headers = Headers.of(
        "cache-control", "no-store",
        "Cache-Control", "no-cache",
        "User-Agent", "OkHttp");
    Map<String, List<String>> headerMap = headers.toMultimap();
    assertThat(headerMap.get("cache-control").size()).isEqualTo(2);
    assertThat(headerMap.get("user-agent").size()).isEqualTo(1);
  }

  @Test public void toMultimapAllowsCaseInsensitiveGet() {
    Headers headers = Headers.of(
        "cache-control", "no-store",
        "Cache-Control", "no-cache");
    Map<String, List<String>> headerMap = headers.toMultimap();
    assertThat(headerMap.get("cache-control").size()).isEqualTo(2);
    assertThat(headerMap.get("Cache-Control").size()).isEqualTo(2);
  }

  @Test public void nameIndexesAreStrict() {
    Headers headers = Headers.of("a", "b", "c", "d");
    try {
      headers.name(-1);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
    assertThat(headers.name(0)).isEqualTo("a");
    assertThat(headers.name(1)).isEqualTo("c");
    try {
      headers.name(2);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void valueIndexesAreStrict() {
    Headers headers = Headers.of("a", "b", "c", "d");
    try {
      headers.value(-1);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
    assertThat(headers.value(0)).isEqualTo("b");
    assertThat(headers.value(1)).isEqualTo("d");
    try {
      headers.value(2);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void builderRejectsUnicodeInHeaderName() {
    try {
      new Headers.Builder().add("héader1", "value1");
      fail("Should have complained about invalid name");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 1 in header name: héader1");
    }
  }

  @Test public void builderRejectsUnicodeInHeaderValue() {
    try {
      new Headers.Builder().add("header1", "valué1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 4 in header1 value: valué1");
    }
  }

  @Test public void varargFactoryRejectsUnicodeInHeaderName() {
    try {
      Headers.of("héader1", "value1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 1 in header name: héader1");
    }
  }

  @Test public void varargFactoryRejectsUnicodeInHeaderValue() {
    try {
      Headers.of("header1", "valué1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 4 in header1 value: valué1");
    }
  }

  @Test public void mapFactoryRejectsUnicodeInHeaderName() {
    try {
      Headers.of(singletonMap("héader1", "value1"));
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 1 in header name: héader1");
    }
  }

  @Test public void mapFactoryRejectsUnicodeInHeaderValue() {
    try {
      Headers.of(singletonMap("header1", "valué1"));
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0xe9 at 4 in header1 value: valué1");
    }
  }

  @Test public void headersEquals() {
    Headers headers1 = new Headers.Builder()
        .add("Connection", "close")
        .add("Transfer-Encoding", "chunked")
        .build();
    Headers headers2 = new Headers.Builder()
        .add("Connection", "close")
        .add("Transfer-Encoding", "chunked")
        .build();
    assertThat(headers2).isEqualTo(headers1);
    assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode());
  }

  @Test public void headersNotEquals() {
    Headers headers1 = new Headers.Builder()
        .add("Connection", "close")
        .add("Transfer-Encoding", "chunked")
        .build();
    Headers headers2 = new Headers.Builder()
        .add("Connection", "keep-alive")
        .add("Transfer-Encoding", "chunked")
        .build();
    assertThat(headers2).isNotEqualTo(headers1);
    assertThat(headers2.hashCode()).isNotEqualTo((long) headers1.hashCode());
  }

  @Test public void headersToString() {
    Headers headers = new Headers.Builder()
        .add("A", "a")
        .add("B", "bb")
        .build();
    assertThat(headers.toString()).isEqualTo("A: a\nB: bb\n");
  }

  @Test public void headersAddAll() {
    Headers sourceHeaders = new Headers.Builder()
        .add("A", "aa")
        .add("a", "aa")
        .add("B", "bb")
        .build();
    Headers headers = new Headers.Builder()
        .add("A", "a")
        .addAll(sourceHeaders)
        .add("C", "c")
        .build();
    assertThat(headers.toString()).isEqualTo("A: a\nA: aa\na: aa\nB: bb\nC: c\n");
  }

  /** See https://github.com/square/okhttp/issues/2780. */
  @Test public void testDigestChallengeWithStrictRfc2617Header() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
            + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithDifferentlyOrderedAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", realm=\"myrealm\", nonce=\"fjalskdflwejrlask"
            + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithDifferentlyOrderedAuthParams2() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaksjdflk"
            + "asdf\", realm=\"myrealm\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithMissingRealm() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", underrealm=\"myrealm\", nonce=\"fjalskdflwej"
            + "rlaskdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isNull();
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("underrealm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithAdditionalSpaces() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\",    realm=\"myrealm\", nonce=\"fjalskdflwejrl"
            + "askdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithAdditionalSpacesBeforeFirstAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest    realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjfl"
            + "aksjdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithCamelCasedNames() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "DiGeSt qop=\"auth\", rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlask"
            + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("DiGeSt");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithCamelCasedNames2() {
    // Strict RFC 2617 camelcased.
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "DIgEsT rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
            + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("DIgEsT");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertThat(challenges.get(0).authParams()).isEqualTo(expectedAuthParams);
  }

  @Test public void testDigestChallengeWithTokenFormOfAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest realm=myrealm").build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isEqualTo("myrealm");
    assertThat(challenges.get(0).authParams()).isEqualTo(singletonMap("realm", "myrealm"));
  }

  @Test public void testDigestChallengeWithoutAuthParams() {
    // Scheme only.
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest").build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertThat(challenges.size()).isEqualTo(1);
    assertThat(challenges.get(0).scheme()).isEqualTo("Digest");
    assertThat(challenges.get(0).realm()).isNull();
    assertThat(challenges.get(0).authParams()).isEqualTo(emptyMap());
  }

  @Test public void basicChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\"")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(new Challenge("Basic", singletonMap("realm", "protected area"))));
  }

  @Test public void basicChallengeWithCharset() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
        .build();
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "protected area");
    expectedAuthParams.put("charset", "UTF-8");
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(new Challenge("Basic", expectedAuthParams)));
  }

  @Test public void basicChallengeWithUnexpectedCharset() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"US-ASCII\"")
        .build();

    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "protected area");
    expectedAuthParams.put("charset", "US-ASCII");
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(new Challenge("Basic", expectedAuthParams)));
  }

  @Test public void separatorsBeforeFirstChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", " ,  , Basic realm=myrealm")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(new Challenge("Basic", singletonMap("realm", "myrealm"))));
  }

  @Test public void spacesAroundKeyValueSeparator() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\"")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(new Challenge("Basic", singletonMap("realm", "myrealm"))));
  }

  @Test public void multipleChallengesInOneHeader() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Digest")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Digest", Collections.emptyMap()));
  }

  @Test public void multipleChallengesWithSameSchemeButDifferentRealmInOneHeader() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Basic realm=myotherrealm")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Basic", singletonMap("realm", "myotherrealm")));
  }

  @Test public void separatorsBeforeFirstAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest, Basic ,,realm=\"myrealm\"")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm")));
  }

  @Test public void onlyCommaBetweenChallenges() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,Basic realm=\"myrealm\"")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm")));
  }

  @Test public void multipleSeparatorsBetweenChallenges() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,realm=\"myrealm\"")
        .build();
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm")));
  }

  @Test public void unknownAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,foo=bar,realm=\"myrealm\"")
        .build();

    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("foo", "bar");
    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", expectedAuthParams));
  }

  @Test public void escapedCharactersInQuotedString() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\"r\\ealm\"")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "my\\\"realm")));
  }

  @Test public void commaInQuotedStringAndBeforeFirstChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", ",Digest,,,, Basic ,,,realm=\"my, realm,\"")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "my, realm,")));
  }

  @Test public void unescapedDoubleQuoteInQuotedStringWithEvenNumberOfBackslashesInFront() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\\\"r\\ealm\"")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()));
  }

  @Test public void unescapedDoubleQuoteInQuotedString() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\"realm\"")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()));
  }

  @Ignore("TODO(jwilson): reject parameters that use invalid characters")
  @Test public void doubleQuoteInToken() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=my\"realm")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()));
  }

  @Test public void token68InsteadOfAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other abc==")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        singletonList(
        new Challenge("Other", singletonMap(null, "abc=="))));
  }

  @Test public void token68AndAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other abc==, realm=myrealm")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Other", singletonMap(null, "abc==")));
  }

  @Test public void repeatedAuthParamKey() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other realm=myotherrealm, realm=myrealm")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).isEqualTo(
        emptyList());
  }

  @Test public void multipleAuthenticateHeaders() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest")
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Digest", Collections.emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm")));
  }

  @Test public void multipleAuthenticateHeadersInDifferentOrder() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Digest")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Digest", Collections.emptyMap()));
  }

  @Test public void multipleBasicAuthenticateHeaders() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Basic realm=myotherrealm")
        .build();

    assertThat(HttpHeaders.parseChallenges(headers, "WWW-Authenticate")).containsExactly(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Basic", singletonMap("realm", "myotherrealm")));
  }

  @Test public void byteCount() {
    assertThat(Util.EMPTY_HEADERS.byteCount()).isEqualTo(0L);
    assertThat(new Headers.Builder()
        .add("abc", "def")
        .build()
        .byteCount()).isEqualTo(10L);
    assertThat(new Headers.Builder()
        .add("abc", "def")
        .add("ghi", "jkl")
        .build()
        .byteCount()).isEqualTo(20L);
  }

  @Test public void addDate() {
    Date expected = new Date(0L);
    Headers headers = new Headers.Builder()
        .add("testDate", expected)
        .build();
    assertThat(headers.get("testDate")).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT");
    assertThat(headers.getDate("testDate")).isEqualTo(new Date(0L));
  }

  @Test public void addDateNull() {
    try {
      new Headers.Builder()
          .add("testDate", (Date) null)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void addInstant() {
    Instant expected = Instant.ofEpochMilli(0L);
    Headers headers = new Headers.Builder()
        .add("Test-Instant", expected)
        .build();
    assertThat(headers.get("Test-Instant")).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT");
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected);
  }

  @Test public void addInstantNull() {
    try {
      new Headers.Builder()
          .add("Test-Instant", (Instant) null)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setDate() {
    Date expected = new Date(1000);
    Headers headers = new Headers.Builder()
        .add("testDate", new Date(0L))
        .set("testDate", expected)
        .build();
    assertThat(headers.get("testDate")).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT");
    assertThat(headers.getDate("testDate")).isEqualTo(expected);
  }

  @Test public void setDateNull() {
    try {
      new Headers.Builder()
          .set("testDate", (Date) null)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setInstant() {
    Instant expected = Instant.ofEpochMilli(1000L);
    Headers headers = new Headers.Builder()
        .add("Test-Instant", Instant.ofEpochMilli(0L))
        .set("Test-Instant", expected)
        .build();
    assertThat(headers.get("Test-Instant")).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT");
    assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected);
  }

  @Test public void setInstantNull() {
    try {
      new Headers.Builder()
          .set("Test-Instant", (Instant) null)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
