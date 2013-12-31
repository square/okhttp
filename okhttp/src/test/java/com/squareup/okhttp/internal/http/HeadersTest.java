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

import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static junit.framework.Assert.assertNull;
import static org.junit.Assert.assertEquals;

public final class HeadersTest {
  @Test public void parseNameValueBlock() throws IOException {
    List<String> nameValueBlock = Arrays.asList(
        "cache-control", "no-cache, no-store",
        "set-cookie", "Cookie1\u0000Cookie2",
        ":status", "200 OK",
        ":version", "HTTP/1.1");
    Request request = new Request.Builder().url("http://square.com/").build();
    Response response = SpdyTransport.readNameValueBlock(request, nameValueBlock).build();
    Headers headers = response.headers();
    assertEquals(4, headers.length());
    assertEquals("HTTP/1.1 200 OK", response.statusLine());
    assertEquals("no-cache, no-store", headers.get("cache-control"));
    assertEquals("Cookie2", headers.get("set-cookie"));
    assertEquals("spdy/3", headers.get(SyntheticHeaders.SELECTED_TRANSPORT));
    assertEquals(SyntheticHeaders.SELECTED_TRANSPORT, headers.getFieldName(0));
    assertEquals("spdy/3", headers.getValue(0));
    assertEquals("cache-control", headers.getFieldName(1));
    assertEquals("no-cache, no-store", headers.getValue(1));
    assertEquals("set-cookie", headers.getFieldName(2));
    assertEquals("Cookie1", headers.getValue(2));
    assertEquals("set-cookie", headers.getFieldName(3));
    assertEquals("Cookie2", headers.getValue(3));
    assertNull(headers.get(":status"));
    assertNull(headers.get(":version"));
  }

  @Test public void toNameValueBlock() {
    Headers.Builder builder = new Headers.Builder();
    builder.add("cache-control", "no-cache, no-store");
    builder.add("set-cookie", "Cookie1");
    builder.add("set-cookie", "Cookie2");
    builder.add(":status", "200 OK");
    List<String> nameValueBlock = SpdyTransport.writeNameValueBlock(builder.build());
    List<String> expected = Arrays.asList(
        "cache-control", "no-cache, no-store",
        "set-cookie", "Cookie1\u0000Cookie2",
        ":status", "200 OK");
    assertEquals(expected, nameValueBlock);
  }

  @Test public void toNameValueBlockDropsForbiddenHeaders() {
    Headers.Builder builder = new Headers.Builder();
    builder.add("Connection", "close");
    builder.add("Transfer-Encoding", "chunked");
    assertEquals(Arrays.<String>asList(), SpdyTransport.writeNameValueBlock(builder.build()));
  }
}
