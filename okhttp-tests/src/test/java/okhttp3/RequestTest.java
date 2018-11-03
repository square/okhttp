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
package okhttp3;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import okhttp3.internal.Util;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class RequestTest {
  @Test public void string() throws Exception {
    MediaType contentType = MediaType.get("text/plain; charset=utf-8");
    RequestBody body = RequestBody.create(contentType, "abc".getBytes(Util.UTF_8));
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  @Test public void stringWithDefaultCharsetAdded() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(contentType, "\u0800");
    assertEquals(MediaType.get("text/plain; charset=utf-8"), body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("e0a080", bodyToHex(body));
  }

  @Test public void stringWithNonDefaultCharsetSpecified() throws Exception {
    MediaType contentType = MediaType.get("text/plain; charset=utf-16be");
    RequestBody body = RequestBody.create(contentType, "\u0800");
    assertEquals(contentType, body.contentType());
    assertEquals(2, body.contentLength());
    assertEquals("0800", bodyToHex(body));
  }

  @Test public void byteArray() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(contentType, "abc".getBytes(Util.UTF_8));
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  @Test public void byteArrayRange() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(contentType, ".abcd".getBytes(Util.UTF_8), 1, 3);
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

    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(contentType, file);
    assertEquals(contentType, body.contentType());
    assertEquals(3, body.contentLength());
    assertEquals("616263", bodyToHex(body));
    assertEquals("Retransmit body", "616263", bodyToHex(body));
  }

  /** Common verbs used for apis such as GitHub, AWS, and Google Cloud. */
  @Test public void crudVerbs() throws IOException {
    MediaType contentType = MediaType.get("application/json");
    RequestBody body = RequestBody.create(contentType, "{}");

    Request get = new Request.Builder().url("http://localhost/api").get().build();
    assertEquals("GET", get.method());
    assertNull(get.body());

    Request head = new Request.Builder().url("http://localhost/api").head().build();
    assertEquals("HEAD", head.method());
    assertNull(head.body());

    Request delete = new Request.Builder().url("http://localhost/api").delete().build();
    assertEquals("DELETE", delete.method());
    assertEquals(0L, delete.body().contentLength());

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

  @Test public void uninitializedURI() throws Exception {
    Request request = new Request.Builder().url("http://localhost/api").build();
    assertEquals(new URI("http://localhost/api"), request.url().uri());
    assertEquals(HttpUrl.get("http://localhost/api"), request.url());
  }

  @Test public void newBuilderUrlResetsUrl() {
    Request requestWithoutCache = new Request.Builder().url("http://localhost/api").build();
    Request builtRequestWithoutCache =
        requestWithoutCache.newBuilder().url("http://localhost/api/foo").build();
    assertEquals(HttpUrl.get("http://localhost/api/foo"), builtRequestWithoutCache.url());

    Request requestWithCache = new Request.Builder().url("http://localhost/api").build();
    // cache url object
    requestWithCache.url();
    Request builtRequestWithCache = requestWithCache.newBuilder().url(
        "http://localhost/api/foo").build();
    assertEquals(HttpUrl.get("http://localhost/api/foo"), builtRequestWithCache.url());
  }

  @Test public void cacheControl() {
    Request request = new Request.Builder()
        .cacheControl(new CacheControl.Builder().noCache().build())
        .url("https://square.com")
        .build();
    assertEquals(Arrays.asList("no-cache"), request.headers("Cache-Control"));
  }

  @Test public void emptyCacheControlClearsAllCacheControlHeaders() {
    Request request = new Request.Builder()
        .header("Cache-Control", "foo")
        .cacheControl(new CacheControl.Builder().build())
        .url("https://square.com")
        .build();
    assertEquals(Collections.<String>emptyList(), request.headers("Cache-Control"));
  }

  @Test public void headerAcceptsPermittedCharacters() {
    Request.Builder builder = new Request.Builder();
    builder.header("AZab09~", "AZab09 ~");
    builder.addHeader("AZab09~", "AZab09 ~");
  }

  @Test public void emptyNameForbidden() {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void headerForbidsNullArguments() {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header(null, "Value");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.addHeader(null, "Value");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.header("Name", null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.addHeader("Name", null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void headerAllowsTabOnlyInValues() {
    Request.Builder builder = new Request.Builder();
    builder.header("key", "sample\tvalue");
    try {
      builder.header("sample\tkey", "value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void headerForbidsControlCharacters() {
    assertForbiddenHeader("\u0000");
    assertForbiddenHeader("\r");
    assertForbiddenHeader("\n");
    assertForbiddenHeader("\u001f");
    assertForbiddenHeader("\u007f");
    assertForbiddenHeader("\u0080");
    assertForbiddenHeader("\ud83c\udf69");
  }

  private void assertForbiddenHeader(String s) {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header(s, "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader(s, "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.header("Name", s);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader("Name", s);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void noTag() {
    Request request = new Request.Builder()
        .url("https://square.com")
        .build();
    assertNull(request.tag());
    assertNull(request.tag(Object.class));
    assertNull(request.tag(UUID.class));
    assertNull(request.tag(String.class));
  }

  @Test public void defaultTag() {
    UUID tag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(tag)
        .build();
    assertSame(tag, request.tag());
    assertSame(tag, request.tag(Object.class));
    assertNull(request.tag(UUID.class));
    assertNull(request.tag(String.class));
  }

  @Test public void nullRemovesTag() {
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag("a")
        .tag(null)
        .build();
    assertNull(request.tag());
  }

  @Test public void removeAbsentTag() {
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(null)
        .build();
    assertNull(request.tag());
  }

  @Test public void objectTag() {
    UUID tag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(Object.class, tag)
        .build();
    assertSame(tag, request.tag());
    assertSame(tag, request.tag(Object.class));
    assertNull(request.tag(UUID.class));
    assertNull(request.tag(String.class));
  }

  @Test public void typedTag() {
    UUID uuidTag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(UUID.class, uuidTag)
        .build();
    assertNull(request.tag());
    assertNull(request.tag(Object.class));
    assertSame(uuidTag, request.tag(UUID.class));
    assertNull(request.tag(String.class));
  }

  @Test public void replaceOnlyTag() {
    UUID uuidTag1 = UUID.randomUUID();
    UUID uuidTag2 = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(UUID.class, uuidTag1)
        .tag(UUID.class, uuidTag2)
        .build();
    assertSame(uuidTag2, request.tag(UUID.class));
  }

  @Test public void multipleTags() {
    UUID uuidTag = UUID.randomUUID();
    String stringTag = "dilophosaurus";
    Long longTag = 20170815L;
    Object objectTag = new Object();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(Object.class, objectTag)
        .tag(UUID.class, uuidTag)
        .tag(String.class, stringTag)
        .tag(Long.class, longTag)
        .build();
    assertSame(objectTag, request.tag());
    assertSame(objectTag, request.tag(Object.class));
    assertSame(uuidTag, request.tag(UUID.class));
    assertSame(stringTag, request.tag(String.class));
    assertSame(longTag, request.tag(Long.class));
  }

  /** Confirm that we don't accidentally share the backing map between objects. */
  @Test public void tagsAreImmutable() {
    Request.Builder builder = new Request.Builder()
        .url("https://square.com");
    Request requestA = builder.tag(String.class, "a").build();
    Request requestB = builder.tag(String.class, "b").build();
    Request requestC = requestA.newBuilder().tag(String.class, "c").build();
    assertSame("a", requestA.tag(String.class));
    assertSame("b", requestB.tag(String.class));
    assertSame("c", requestC.tag(String.class));
  }

  private String bodyToHex(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    return buffer.readByteString().hex();
  }
}
