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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.spdy.Header;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class HeadersTest {
  @Test public void parseNameValueBlock() throws IOException {
    List<Header> headerBlock = headerEntries(
        "cache-control", "no-cache, no-store",
        "set-cookie", "Cookie1\u0000Cookie2",
        ":status", "200 OK",
        ":version", "HTTP/1.1");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response =
        SpdyTransport.readNameValueBlock(headerBlock, Protocol.SPDY_3).request(request).build();
    Headers headers = response.headers();
    assertEquals(4, headers.size());
    assertEquals("HTTP/1.1 200 OK", response.statusLine());
    assertEquals("no-cache, no-store", headers.get("cache-control"));
    assertEquals("Cookie2", headers.get("set-cookie"));
    assertEquals(Protocol.SPDY_3.toString(), headers.get(OkHeaders.SELECTED_PROTOCOL));
    assertEquals(OkHeaders.SELECTED_PROTOCOL, headers.name(0));
    assertEquals(Protocol.SPDY_3.toString(), headers.value(0));
    assertEquals("cache-control", headers.name(1));
    assertEquals("no-cache, no-store", headers.value(1));
    assertEquals("set-cookie", headers.name(2));
    assertEquals("Cookie1", headers.value(2));
    assertEquals("set-cookie", headers.name(3));
    assertEquals("Cookie2", headers.value(3));
    assertNull(headers.get(":status"));
    assertNull(headers.get(":version"));
  }

  @Test public void readNameValueBlockDropsForbiddenHeadersSpdy3() throws IOException {
    List<Header> headerBlock = headerEntries(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response =
        SpdyTransport.readNameValueBlock(headerBlock, Protocol.SPDY_3).request(request).build();
    Headers headers = response.headers();
    assertEquals(1, headers.size());
    assertEquals(OkHeaders.SELECTED_PROTOCOL, headers.name(0));
    assertEquals(Protocol.SPDY_3.toString(), headers.value(0));
  }

  @Test public void readNameValueBlockDropsForbiddenHeadersHttp2() throws IOException {
    List<Header> headerBlock = headerEntries(
        ":status", "200 OK",
        ":version", "HTTP/1.1",
        "connection", "close");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = SpdyTransport.readNameValueBlock(headerBlock, Protocol.HTTP_2)
        .request(request).build();
    Headers headers = response.headers();
    assertEquals(1, headers.size());
    assertEquals(OkHeaders.SELECTED_PROTOCOL, headers.name(0));
    assertEquals(Protocol.HTTP_2.toString(), headers.value(0));
  }

  @Test public void toNameValueBlock() {
    Request request = new Request.Builder()
        .url("http://square.com/")
        .header("cache-control", "no-cache, no-store")
        .addHeader("set-cookie", "Cookie1")
        .addHeader("set-cookie", "Cookie2")
        .header(":status", "200 OK")
        .build();
    List<Header> headerBlock =
        SpdyTransport.writeNameValueBlock(request, Protocol.SPDY_3, "HTTP/1.1");
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

  @Test public void toNameValueBlockDropsForbiddenHeadersSpdy3() {
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
    assertEquals(expected, SpdyTransport.writeNameValueBlock(request, Protocol.SPDY_3, "HTTP/1.1"));
  }

  @Test public void toNameValueBlockDropsForbiddenHeadersHttp2() {
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
    assertEquals(expected,
        SpdyTransport.writeNameValueBlock(request, Protocol.HTTP_2, "HTTP/1.1"));
  }
}
