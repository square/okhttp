/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class RequestTest {
  @Test public void string() throws Exception {
    MediaType contentType = MediaType.parse("text/plain; charset=utf-8");
    RequestBody body = RequestBody.create(contentType, "abc".getBytes(Util.UTF_8));
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  @Test public void stringWithDefaultCharsetAdded() throws Exception {
    MediaType contentType = MediaType.parse("text/plain");
    RequestBody body = RequestBody.create(contentType, "\u0800");
    assertEquals(MediaType.parse("text/plain; charset=utf-8"), body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("e0a080", bodyToHex(body));
  }

  @Test public void stringWithNonDefaultCharsetSpecified() throws Exception {
    MediaType contentType = MediaType.parse("text/plain; charset=utf-16be");
    RequestBody body = RequestBody.create(contentType, "\u0800");
    assertEquals(contentType, body.contentType());
    assertEquals(2, body.contentLength());
    assertEquals("0800", bodyToHex(body));
  }

  @Test public void byteArray() throws Exception {
    MediaType contentType = MediaType.parse("text/plain");
    RequestBody body = RequestBody.create(contentType, "abc".getBytes(Util.UTF_8));
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  @Test public void file() throws Exception {
    File file = File.createTempFile("RequestTest", "tmp");
    FileWriter writer = new FileWriter(file);
    writer.write("abc");
    writer.close();

    MediaType contentType = MediaType.parse("text/plain");
    RequestBody body = RequestBody.create(contentType, file);
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  /** Common verbs used for apis such as GitHub, AWS, and Google Cloud. */
  @Test public void crudVerbs() {
    MediaType contentType = MediaType.parse("application/json");
    RequestBody body = RequestBody.create(contentType, "{}");

    Request get = new Request.Builder().url("http://localhost/api").get().build();
    assertEquals("GET", get.method());
    assertNull(get.body());

    Request head = new Request.Builder().url("http://localhost/api").head().build();
    assertEquals("HEAD", head.method());
    assertNull(head.body());

    Request delete = new Request.Builder().url("http://localhost/api").delete().build();
    assertEquals("DELETE", delete.method());
    assertNull(delete.body());

    Request post = new Request.Builder().url("http://localhost/api").post(body).build();
    assertEquals("POST", post.method());
    assertEquals(body, post.body());

    Request put = new Request.Builder().url("http://localhost/api").put(body).build();
    assertEquals("PUT", put.method());
    assertEquals(body, put.body());

    Request patch = new Request.Builder().url("http://localhost/api").patch(body).build();
    assertEquals("PATCH", patch.method());
    assertEquals(body, patch.body());
  }

  private String bodyToHex(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    return buffer.readByteString().hex();
  }
}
