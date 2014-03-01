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
import java.io.IOException;
import okio.OkBuffer;
import org.junit.Test;

import static com.squareup.okhttp.curl.Main.fromArgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MainTest {
  @Test public void simple() {
    Request request = fromArgs("http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com", request.urlString());
    assertNull(request.body());
  }

  @Test public void put() {
    Request request = fromArgs("-X", "PUT", "http://example.com").createRequest();
    assertEquals("PUT", request.method());
    assertEquals("http://example.com", request.urlString());
    assertNull(request.body());
  }

  @Test public void dataPost() {
    Request request = fromArgs("-d", "foo", "http://example.com").createRequest();
    Request.Body body = request.body();
    assertEquals("POST", request.method());
    assertEquals("http://example.com", request.urlString());
    assertEquals("application/x-form-urlencoded; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void dataPut() {
    Request request = fromArgs("-d", "foo", "-X", "PUT", "http://example.com").createRequest();
    Request.Body body = request.body();
    assertEquals("PUT", request.method());
    assertEquals("http://example.com", request.urlString());
    assertEquals("application/x-form-urlencoded; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void contentTypeHeader() {
    Request request = fromArgs("-d", "foo", "-H", "Content-Type: application/json",
        "http://example.com").createRequest();
    Request.Body body = request.body();
    assertEquals("POST", request.method());
    assertEquals("http://example.com", request.urlString());
    assertEquals("application/json; charset=utf-8", body.contentType().toString());
    assertEquals("foo", bodyAsString(body));
  }

  @Test public void referer() {
    Request request = fromArgs("-e", "foo", "http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com", request.urlString());
    assertEquals("foo", request.header("Referer"));
    assertNull(request.body());
  }

  @Test public void userAgent() {
    Request request = fromArgs("-A", "foo", "http://example.com").createRequest();
    assertEquals("GET", request.method());
    assertEquals("http://example.com", request.urlString());
    assertEquals("foo", request.header("User-Agent"));
    assertNull(request.body());
  }

  private static String bodyAsString(Request.Body body) {
    try {
      OkBuffer buffer = new OkBuffer();
      body.writeTo(buffer);
      return new String(buffer.readByteString(buffer.size()).toByteArray(),
          body.contentType().charset());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
