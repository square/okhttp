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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.internal.Internal;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Codec;
import org.junit.Ignore;
import org.junit.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static okhttp3.TestUtil.headerEntries;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HeadersTest {
  static {
    Internal.initializeInstanceForTests();
  }

  @Test public void readNameValueBlockDropsForbiddenHeadersHttp2() throws IOException {
    Headers headerBlock = Headers.of(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = Http2Codec.readHttp2HeadersList(headerBlock, Protocol.HTTP_2).request(request).build();
    Headers headers = response.headers();
    assertEquals(1, headers.size());
    assertEquals(":version", headers.name(0));
    assertEquals("HTTP/1.1", headers.value(0));
  }

  @Test public void http2HeadersListDropsForbiddenHeadersHttp2() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("Connection", "upgrade")
        .header("Upgrade", "websocket")
        .header("Host", "square.com")
        .build();
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":authority", "square.com",
        ":scheme", "http");
    assertEquals(expected, Http2Codec.http2HeadersList(request));
  }

  @Test public void ofTrims() {
    Headers headers = Headers.of("\t User-Agent \n", " \r OkHttp ");
    assertEquals("User-Agent", headers.name(0));
    assertEquals("OkHttp", headers.value(0));
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
    assertEquals(Arrays.asList("bar", "baz", "bak"), headers.values("foo"));
    assertEquals(Arrays.asList("value"), headers.values("key"));
    assertEquals(Arrays.asList("pong"), headers.values("ping"));
    assertEquals(Arrays.asList("kat"), headers.values("kit"));
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
      Headers headers = new Headers.Builder()
          .addUnsafeNonAscii("héader1", "value1")
          .build();
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 1 in header name: héader1",
          expected.getMessage());
    }
  }

  @Test public void addUnsafeNonAsciiAcceptsUnicodeValue() {
    Headers headers = new Headers.Builder()
        .addUnsafeNonAscii("header1", "valué1")
        .build();
    assertEquals("header1: valué1\n", headers.toString());
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
    assertEquals("", headers.value(0));
  }

  @Test public void ofMakesDefensiveCopy() {
    String[] namesAndValues = {
        "User-Agent",
        "OkHttp"
    };
    Headers headers = Headers.of(namesAndValues);
    namesAndValues[1] = "Chrome";
    assertEquals("OkHttp", headers.value(0));
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
      Headers.of(Collections.<String, String>singletonMap("User-Agent", null));
      fail();
    } catch (IllegalArgumentException expected) {
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
    assertEquals("", headers.value(0));
  }

  @Test public void ofMapTrimsKey() {
    Headers headers = Headers.of(singletonMap(" User-Agent ", "OkHttp"));
    assertEquals("User-Agent", headers.name(0));
  }

  @Test public void ofMapTrimsValue() {
    Headers headers = Headers.of(singletonMap("User-Agent", " OkHttp "));
    assertEquals("OkHttp", headers.value(0));
  }

  @Test public void ofMapMakesDefensiveCopy() {
    Map<String, String> namesAndValues = new LinkedHashMap<>();
    namesAndValues.put("User-Agent", "OkHttp");

    Headers headers = Headers.of(namesAndValues);
    namesAndValues.put("User-Agent", "Chrome");
    assertEquals("OkHttp", headers.value(0));
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
    assertEquals(2, headerMap.get("cache-control").size());
    assertEquals(1, headerMap.get("user-agent").size());
  }

  @Test public void toMultimapUsesCanonicalCase() {
    Headers headers = Headers.of(
        "cache-control", "no-store",
        "Cache-Control", "no-cache",
        "User-Agent", "OkHttp");
    Map<String, List<String>> headerMap = headers.toMultimap();
    assertEquals(2, headerMap.get("cache-control").size());
    assertEquals(1, headerMap.get("user-agent").size());
  }

  @Test public void toMultimapAllowsCaseInsensitiveGet() {
    Headers headers = Headers.of(
        "cache-control", "no-store",
        "Cache-Control", "no-cache");
    Map<String, List<String>> headerMap = headers.toMultimap();
    assertEquals(2, headerMap.get("cache-control").size());
    assertEquals(2, headerMap.get("Cache-Control").size());
  }

  @Test public void nameIndexesAreStrict() {
    Headers headers = Headers.of("a", "b", "c", "d");
    try {
      headers.name(-1);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
    assertEquals("a", headers.name(0));
    assertEquals("c", headers.name(1));
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
    assertEquals("b", headers.value(0));
    assertEquals("d", headers.value(1));
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
      assertEquals("Unexpected char 0xe9 at 1 in header name: héader1",
          expected.getMessage());
    }
  }

  @Test public void builderRejectsUnicodeInHeaderValue() {
    try {
      new Headers.Builder().add("header1", "valué1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 4 in header1 value: valué1",
          expected.getMessage());
    }
  }

  @Test public void varargFactoryRejectsUnicodeInHeaderName() {
    try {
      Headers.of("héader1", "value1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 1 in header name: héader1",
          expected.getMessage());
    }
  }

  @Test public void varargFactoryRejectsUnicodeInHeaderValue() {
    try {
      Headers.of("header1", "valué1");
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 4 in header1 value: valué1",
          expected.getMessage());
    }
  }

  @Test public void mapFactoryRejectsUnicodeInHeaderName() {
    try {
      Headers.of(singletonMap("héader1", "value1"));
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 1 in header name: héader1",
          expected.getMessage());
    }
  }

  @Test public void mapFactoryRejectsUnicodeInHeaderValue() {
    try {
      Headers.of(singletonMap("header1", "valué1"));
      fail("Should have complained about invalid value");
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0xe9 at 4 in header1 value: valué1",
          expected.getMessage());
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
    assertTrue(headers1.equals(headers2));
    assertEquals(headers1.hashCode(), headers2.hashCode());
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
    assertFalse(headers1.equals(headers2));
    assertFalse(headers1.hashCode() == headers2.hashCode());
  }

  @Test public void headersToString() {
    Headers headers = new Headers.Builder()
        .add("A", "a")
        .add("B", "bb")
        .build();
    assertEquals("A: a\nB: bb\n", headers.toString());
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
    assertEquals("A: a\nA: aa\na: aa\nB: bb\nC: c\n", headers.toString());
  }

  /** See https://github.com/square/okhttp/issues/2780. */
  @Test public void testDigestChallengeWithStrictRfc2617Header() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
            + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithDifferentlyOrderedAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", realm=\"myrealm\", nonce=\"fjalskdflwejrlask"
            + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithDifferentlyOrderedAuthParams2() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaksjdflk"
            + "asdf\", realm=\"myrealm\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithMissingRealm() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\", underrealm=\"myrealm\", nonce=\"fjalskdflwej"
            + "rlaskdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertNull(challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("underrealm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithAdditionalSpaces() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest qop=\"auth\",    realm=\"myrealm\", nonce=\"fjalskdflwejrl"
            + "askdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithAdditionalSpacesBeforeFirstAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest    realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjfl"
            + "aksjdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithCamelCasedNames() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "DiGeSt qop=\"auth\", rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlask"
            + "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("DiGeSt", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithCamelCasedNames2() {
    // Strict RFC 2617 camelcased.
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "DIgEsT rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks"
            + "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"")
        .build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("DIgEsT", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("nonce", "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf");
    expectedAuthParams.put("qop", "auth");
    expectedAuthParams.put("stale", "FALSE");
    assertEquals(expectedAuthParams, challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithTokenFormOfAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest realm=myrealm").build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertEquals("myrealm", challenges.get(0).realm());
    assertEquals(singletonMap("realm", "myrealm"), challenges.get(0).authParams());
  }

  @Test public void testDigestChallengeWithoutAuthParams() {
    // Scheme only.
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest").build();
    List<Challenge> challenges = HttpHeaders.parseChallenges(headers, "WWW-Authenticate");
    assertEquals(1, challenges.size());
    assertEquals("Digest", challenges.get(0).scheme());
    assertNull(challenges.get(0).realm());
    assertEquals(emptyMap(), challenges.get(0).authParams());
  }

  @Test public void basicChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\"")
        .build();
    assertEquals(singletonList(new Challenge("Basic", singletonMap("realm", "protected area"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void basicChallengeWithCharset() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
        .build();
    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "protected area");
    expectedAuthParams.put("charset", "UTF-8");
    assertEquals(singletonList(new Challenge("Basic", expectedAuthParams)),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void basicChallengeWithUnexpectedCharset() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"US-ASCII\"")
        .build();

    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "protected area");
    expectedAuthParams.put("charset", "US-ASCII");
    assertEquals(singletonList(new Challenge("Basic", expectedAuthParams)),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void separatorsBeforeFirstChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", " ,  , Basic realm=myrealm")
        .build();
    assertEquals(singletonList(new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void spacesAroundKeyValueSeparator() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\"")
        .build();
    assertEquals(singletonList(new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleChallengesInOneHeader() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Digest")
        .build();
    assertEquals(Arrays.asList(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Digest", Collections.<String, String>emptyMap())),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleChallengesWithSameSchemeButDifferentRealmInOneHeader() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Basic realm=myotherrealm")
        .build();
    assertEquals(Arrays.asList(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Basic", singletonMap("realm", "myotherrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void separatorsBeforeFirstAuthParam() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest, Basic ,,realm=\"myrealm\"")
        .build();
    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void onlyCommaBetweenChallenges() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,Basic realm=\"myrealm\"")
        .build();
    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleSeparatorsBetweenChallenges() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,realm=\"myrealm\"")
        .build();
    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void unknownAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,foo=bar,realm=\"myrealm\"")
        .build();

    Map<String, String> expectedAuthParams = new LinkedHashMap<>();
    expectedAuthParams.put("realm", "myrealm");
    expectedAuthParams.put("foo", "bar");
    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", expectedAuthParams)),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void escapedCharactersInQuotedString() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\"r\\ealm\"")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "my\\\"realm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void commaInQuotedStringAndBeforeFirstChallenge() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", ",Digest,,,, Basic ,,,realm=\"my, realm,\"")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "my, realm,"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void unescapedDoubleQuoteInQuotedStringWithEvenNumberOfBackslashesInFront() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\\\"r\\ealm\"")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap())),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void unescapedDoubleQuoteInQuotedString() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\"realm\"")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap())),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Ignore("TODO(jwilson): reject parameters that use invalid characters")
  @Test public void doubleQuoteInToken() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=my\"realm")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap())),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void token68InsteadOfAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other abc==")
        .build();

    assertEquals(singletonList(
        new Challenge("Other", singletonMap(((String) null), "abc=="))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void token68AndAuthParams() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other abc==, realm=myrealm")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Other", singletonMap((String) null, "abc=="))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void repeatedAuthParamKey() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Other realm=myotherrealm, realm=myrealm")
        .build();

    assertEquals(emptyList(), HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleAuthenticateHeaders() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Digest")
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Digest", Collections.<String, String>emptyMap()),
        new Challenge("Basic", singletonMap("realm", "myrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleAuthenticateHeadersInDifferentOrder() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Digest")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Digest", Collections.<String, String>emptyMap())),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void multipleBasicAuthenticateHeaders() {
    Headers headers = new Headers.Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Basic realm=myotherrealm")
        .build();

    assertEquals(Arrays.asList(
        new Challenge("Basic", singletonMap("realm", "myrealm")),
        new Challenge("Basic", singletonMap("realm", "myotherrealm"))),
        HttpHeaders.parseChallenges(headers, "WWW-Authenticate"));
  }

  @Test public void byteCount() {
    assertEquals(0L, new Headers.Builder().build().byteCount());
    assertEquals(10L, new Headers.Builder()
        .add("abc", "def")
        .build()
        .byteCount());
    assertEquals(20L, new Headers.Builder()
        .add("abc", "def")
        .add("ghi", "jkl")
        .build()
        .byteCount());
  }

  @Test public void addDate() {
    Date expected = new Date(0);
    Headers headers = new Headers.Builder()
        .add("testDate", expected)
        .build();
    assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", headers.get("testDate"));
  }

  @Test public void addDateNull() {
    try {
      new Headers.Builder()
          .add("testDate", (Date) null)
          .build();
      fail();
    } catch (NullPointerException expected) {
      assertEquals("value for name testDate == null", expected.getMessage());
    }
  }

  @Test public void setDate() {
    Date expected = new Date(1000);
    Headers headers = new Headers.Builder()
        .add("testDate", new Date(0))
        .set("testDate", expected)
        .build();
    assertEquals("Thu, 01 Jan 1970 00:00:01 GMT", headers.get("testDate"));
  }

  @Test public void setDateNull() {
    try {
      new Headers.Builder()
          .set("testDate", (Date) null)
          .build();
      fail();
    } catch (NullPointerException expected) {
      assertEquals("value for name testDate == null", expected.getMessage());
    }
  }
}
