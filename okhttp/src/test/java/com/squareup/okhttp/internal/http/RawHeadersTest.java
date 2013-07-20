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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RawHeadersTest {
  @Test public void parseNameValueBlock() throws IOException {
    List<String> nameValueBlock =
        Arrays.asList("cache-control", "no-cache, no-store", "set-cookie", "Cookie1\u0000Cookie2",
            ":status", "200 OK");
    // TODO: fromNameValueBlock should synthesize a request status line
    RawHeaders rawHeaders = RawHeaders.fromNameValueBlock(nameValueBlock);
    assertEquals("no-cache, no-store", rawHeaders.get("cache-control"));
    assertEquals("Cookie2", rawHeaders.get("set-cookie"));
    assertEquals("200 OK", rawHeaders.get(":status"));
    assertEquals("cache-control", rawHeaders.getFieldName(0));
    assertEquals("no-cache, no-store", rawHeaders.getValue(0));
    assertEquals("set-cookie", rawHeaders.getFieldName(1));
    assertEquals("Cookie1", rawHeaders.getValue(1));
    assertEquals("set-cookie", rawHeaders.getFieldName(2));
    assertEquals("Cookie2", rawHeaders.getValue(2));
    assertEquals(":status", rawHeaders.getFieldName(3));
    assertEquals("200 OK", rawHeaders.getValue(3));
  }

  @Test public void toNameValueBlock() {
    RawHeaders rawHeaders = new RawHeaders();
    rawHeaders.add("cache-control", "no-cache, no-store");
    rawHeaders.add("set-cookie", "Cookie1");
    rawHeaders.add("set-cookie", "Cookie2");
    rawHeaders.add(":status", "200 OK");
    // TODO: fromNameValueBlock should take the status line headers
    List<String> nameValueBlock = rawHeaders.toNameValueBlock();
    List<String> expected =
        Arrays.asList("cache-control", "no-cache, no-store", "set-cookie", "Cookie1\u0000Cookie2",
            ":status", "200 OK");
    assertEquals(expected, nameValueBlock);
  }

  @Test public void toNameValueBlockDropsForbiddenHeaders() {
    RawHeaders rawHeaders = new RawHeaders();
    rawHeaders.add("Connection", "close");
    rawHeaders.add("Transfer-Encoding", "chunked");
    assertEquals(Arrays.<String>asList(), rawHeaders.toNameValueBlock());
  }

  @Test public void statusMessage() throws IOException {
    RawHeaders rawHeaders = new RawHeaders();
    final String message = "Temporary Redirect";
    final int version = 1;
    final int code = 200;
    rawHeaders.setStatusLine("HTTP/1." + version + " " + code + " " + message);
    assertEquals(message, rawHeaders.getResponseMessage());
    assertEquals(version, rawHeaders.getHttpMinorVersion());
    assertEquals(code, rawHeaders.getResponseCode());
  }

  @Test public void statusMessageWithEmptyMessage() throws IOException {
    RawHeaders rawHeaders = new RawHeaders();
    final int version = 1;
    final int code = 503;
    rawHeaders.setStatusLine("HTTP/1." + version + " " + code + " ");
    assertEquals("", rawHeaders.getResponseMessage());
    assertEquals(version, rawHeaders.getHttpMinorVersion());
    assertEquals(code, rawHeaders.getResponseCode());
  }

  /**
   * This is not defined in the protocol but some servers won't add the leading
   * empty space when the message is empty.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1
   */
  @Test public void statusMessageWithEmptyMessageAndNoLeadingSpace() throws IOException {
    RawHeaders rawHeaders = new RawHeaders();
    final int version = 1;
    final int code = 503;
    rawHeaders.setStatusLine("HTTP/1." + version + " " + code);
    assertEquals("", rawHeaders.getResponseMessage());
    assertEquals(version, rawHeaders.getHttpMinorVersion());
    assertEquals(code, rawHeaders.getResponseCode());
  }
}
