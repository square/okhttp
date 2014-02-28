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
import java.net.ProtocolException;
import org.junit.Test;

import static junit.framework.Assert.fail;
import static org.junit.Assert.assertEquals;

public final class StatusLineTest {
  @Test public void parse() throws IOException {
    String message = "Temporary Redirect";
    int version = 1;
    int code = 200;
    StatusLine statusLine = new StatusLine("HTTP/1." + version + " " + code + " " + message);
    assertEquals(message, statusLine.message());
    assertEquals(version, statusLine.httpMinorVersion());
    assertEquals(code, statusLine.code());
  }

  @Test public void emptyMessage() throws IOException {
    int version = 1;
    int code = 503;
    StatusLine statusLine = new StatusLine("HTTP/1." + version + " " + code + " ");
    assertEquals("", statusLine.message());
    assertEquals(version, statusLine.httpMinorVersion());
    assertEquals(code, statusLine.code());
  }

  /**
   * This is not defined in the protocol but some servers won't add the leading
   * empty space when the message is empty.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1
   */
  @Test public void emptyMessageAndNoLeadingSpace() throws IOException {
    int version = 1;
    int code = 503;
    StatusLine statusLine = new StatusLine("HTTP/1." + version + " " + code);
    assertEquals("", statusLine.message());
    assertEquals(version, statusLine.httpMinorVersion());
    assertEquals(code, statusLine.code());
  }

  // https://github.com/square/okhttp/issues/386
  @Test public void shoutcast() throws IOException {
    StatusLine statusLine = new StatusLine("ICY 200 OK");
    assertEquals("OK", statusLine.message());
    assertEquals(0, statusLine.httpMinorVersion());
    assertEquals(200, statusLine.code());
  }

  @Test public void missingProtocol() throws IOException {
    assertInvalid("");
    assertInvalid(" ");
    assertInvalid("200 OK");
    assertInvalid(" 200 OK");
  }

  @Test public void protocolVersions() throws IOException {
    assertInvalid("HTTP/2.0 200 OK");
    assertInvalid("HTTP/2.1 200 OK");
    assertInvalid("HTTP/-.1 200 OK");
    assertInvalid("HTTP/1.- 200 OK");
    assertInvalid("HTTP/0.1 200 OK");
    assertInvalid("HTTP/101 200 OK");
    assertInvalid("HTTP/1.1_200 OK");
  }

  @Test public void nonThreeDigitCode() throws IOException {
    assertInvalid("HTTP/1.1  OK");
    assertInvalid("HTTP/1.1 2 OK");
    assertInvalid("HTTP/1.1 20 OK");
    assertInvalid("HTTP/1.1 2000 OK");
    assertInvalid("HTTP/1.1 two OK");
    assertInvalid("HTTP/1.1 2");
    assertInvalid("HTTP/1.1 2000");
    assertInvalid("HTTP/1.1 two");
  }

  @Test public void truncated() throws IOException {
    assertInvalid("");
    assertInvalid("H");
    assertInvalid("HTTP/1");
    assertInvalid("HTTP/1.");
    assertInvalid("HTTP/1.1");
    assertInvalid("HTTP/1.1 ");
    assertInvalid("HTTP/1.1 2");
    assertInvalid("HTTP/1.1 20");
  }

  @Test public void wrongMessageDelimiter() throws IOException {
    assertInvalid("HTTP/1.1 200_");
  }

  private void assertInvalid(String statusLine) throws IOException {
    try {
      new StatusLine(statusLine);
      fail();
    } catch (ProtocolException expected) {
    }
  }
}
