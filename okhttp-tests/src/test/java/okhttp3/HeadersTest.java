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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.internal.framed.Header;
import okhttp3.internal.http.Http2xStream;
import org.junit.Assert;
import org.junit.Test;

import static okhttp3.TestUtil.headerEntries;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HeadersTest {
  @Test public void parseNameValueBlock() throws IOException {
    List<Header> headerBlock = headerEntries(
        "cache-control", "no-cache, no-store",
        "set-cookie", "Cookie1\u0000Cookie2",
        ":status", "200 OK",
        ":version", "HTTP/1.1");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = Http2xStream.readSpdy3HeadersList(headerBlock).request(request).build();
    Headers headers = response.headers();
    assertEquals(3, headers.size());
    Assert.assertEquals(Protocol.SPDY_3, response.protocol());
    assertEquals(200, response.code());
    assertEquals("OK", response.message());
    assertEquals("no-cache, no-store", headers.get("cache-control"));
    assertEquals("Cookie2", headers.get("set-cookie"));
    assertEquals("cache-control", headers.name(0));
    assertEquals("no-cache, no-store", headers.value(0));
    assertEquals("set-cookie", headers.name(1));
    assertEquals("Cookie1", headers.value(1));
    assertEquals("set-cookie", headers.name(2));
    assertEquals("Cookie2", headers.value(2));
    assertNull(headers.get(":status"));
    assertNull(headers.get(":version"));
  }

  @Test public void readNameValueBlockDropsForbiddenHeadersSpdy3() throws IOException {
    List<Header> headerBlock = headerEntries(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = Http2xStream.readSpdy3HeadersList(headerBlock).request(request).build();
    Headers headers = response.headers();
    assertEquals(0, headers.size());
  }

  @Test public void readNameValueBlockDropsForbiddenHeadersHttp2() throws IOException {
    List<Header> headerBlock = headerEntries(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = Http2xStream.readHttp2HeadersList(headerBlock).request(request).build();
    Headers headers = response.headers();
    assertEquals(1, headers.size());
    assertEquals(":version", headers.name(0));
    assertEquals("HTTP/1.1", headers.value(0));
  }

  @Test public void spdy3HeadersList() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("cache-control", "no-cache, no-store")
        .addHeader("set-cookie", "Cookie1")
        .addHeader("set-cookie", "Cookie2")
        .header(":status", "200 OK")
        .build();
    List<Header> headerBlock = Http2xStream.spdy3HeadersList(request);
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":version", "HTTP/1.1",
        ":host", "square.com",
        ":scheme", "http",
        "cache-control", "no-cache, no-store",
        "set-cookie", "Cookie1\u0000Cookie2",
        ":status", "200 OK");
    assertEquals(expected, headerBlock);
  }

  @Test public void spdy3HeadersListDropsForbiddenHeadersSpdy3() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("Connection", "close")
        .header("Transfer-Encoding", "chunked")
        .build();
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":version", "HTTP/1.1",
        ":host", "square.com",
        ":scheme", "http");
    assertEquals(expected, Http2xStream.spdy3HeadersList(request));
  }

  @Test public void http2HeadersListDropsForbiddenHeadersHttp2() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("Connection", "upgrade")
        .header("Upgrade", "websocket")
        .build();
    List<Header> expected = headerEntries(
        ":method", "GET",
        ":path", "/",
        ":authority", "square.com",
        ":scheme", "http");
    assertEquals(expected, Http2xStream.http2HeadersList(request));
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
        .add("ping:  pong  ") // Value whitespace is trimmed.
        .add("kit:kat") // Space after colon is not required.
        .build();
    assertEquals(Arrays.asList("bar", "baz", "bak"), headers.values("foo"));
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

  @Test public void ofRejectsNulChar() {
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
      Headers.of(Collections.singletonMap("", "OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapThrowsOnBlankName() {
    try {
      Headers.of(Collections.singletonMap(" ", "OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapAcceptsEmptyValue() {
    Headers headers = Headers.of(Collections.singletonMap("User-Agent", ""));
    assertEquals("", headers.value(0));
  }

  @Test public void ofMapTrimsKey() {
    Headers headers = Headers.of(Collections.singletonMap(" User-Agent ", "OkHttp"));
    assertEquals("User-Agent", headers.name(0));
  }

  @Test public void ofMapTrimsValue() {
    Headers headers = Headers.of(Collections.singletonMap("User-Agent", " OkHttp "));
    assertEquals("OkHttp", headers.value(0));
  }

  @Test public void ofMapMakesDefensiveCopy() {
    Map<String, String> namesAndValues = new HashMap<>();
    namesAndValues.put("User-Agent", "OkHttp");

    Headers headers = Headers.of(namesAndValues);
    namesAndValues.put("User-Agent", "Chrome");
    assertEquals("OkHttp", headers.value(0));
  }

  @Test public void ofMapRejectsNulCharInName() {
    try {
      Headers.of(Collections.singletonMap("User-Agent", "Square\u0000OkHttp"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void ofMapRejectsNulCharInValue() {
    try {
      Headers.of(Collections.singletonMap("User-\u0000Agent", "OkHttp"));
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
}
