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
import org.junit.Test;

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
}
