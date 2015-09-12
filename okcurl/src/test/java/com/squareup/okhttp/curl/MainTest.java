/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.curl;

import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import java.io.IOException;
import okio.Buffer;
import org.junit.Test;

import static com.squareup.okhttp.curl.Main.fromArgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MainTest {
  @Test public void simple() {
    Request request = fromArgs("http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertNull(request.body());
  }

  @Test public void put() throws IOException {
    Request request = fromArgs("-X", "PUT", "-d", "foo", "http://example.com").createRequest();
    assertEquals("PUT", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals(3, request.body().contentLength());
  }

  @Test public void dataPost() {
    Request request = fromArgs("-d", "foo", "http://example.com").createRequest();
    RequestBody body = request.body();
    assertEquals("POST", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals("application/x-www-form-urlencoded; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void dataPut() {
    Request request = fromArgs("-d", "foo", "-X", "PUT", "http://example.com").createRequest();
    RequestBody body = request.body();
    assertEquals("PUT", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals("application/x-www-form-urlencoded; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void contentTypeHeader() {
    Request request = fromArgs("-d", "foo", "-H", "Content-Type: application/json",
        "http://example.com").createRequest();
    RequestBody body = request.body();
    assertEquals("POST", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals("application/json; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void referer() {
    Request request = fromArgs("-e", "foo", "http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals("foo", request.header("Referer"));
    assertNull(request.body());
  }

  @Test public void userAgent() {
    Request request = fromArgs("-A", "foo", "http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com/", request.urlString());
    assertEquals("foo", request.header("User-Agent"));
    assertNull(request.body());
  }

  @Test public void headerSplitWithDate() {
    Request request = fromArgs("-H", "If-Modified-Since: Mon, 18 Aug 2014 15:16:06 GMT",
        "http://example.com").createRequest();
    assertEquals("Mon, 18 Aug 2014 15:16:06 GMT", request.header("If-Modified-Since"));
  }

  private static String bodyAsString(RequestBody body) {
    try {
      Buffer buffer = new Buffer();
      body.writeTo(buffer);
      return buffer.readString(body.contentType().charset());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
