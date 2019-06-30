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
import java.util.UUID;
import okio.Buffer;
import okio.ByteString;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class RequestTest {
  @Test public void string() throws Exception {
    MediaType contentType = MediaType.get("text/plain; charset=utf-8");
    RequestBody body = RequestBody.create("abc".getBytes(UTF_8), contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("616263");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo(
        "616263");
  }

  @Test public void stringWithDefaultCharsetAdded() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create("\u0800", contentType);
    assertThat(body.contentType()).isEqualTo(MediaType.get("text/plain; charset=utf-8"));
    assertThat(body.contentLength()).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("e0a080");
  }

  @Test public void stringWithNonDefaultCharsetSpecified() throws Exception {
    MediaType contentType = MediaType.get("text/plain; charset=utf-16be");
    RequestBody body = RequestBody.create("\u0800", contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(2);
    assertThat(bodyToHex(body)).isEqualTo("0800");
  }

  @Test public void byteArray() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create("abc".getBytes(UTF_8), contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("616263");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo(
        "616263");
  }

  @Test public void byteArrayRange() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(".abcd".getBytes(UTF_8), contentType, 1, 3);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("616263");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo(
        "616263");
  }

  @Test public void byteString() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(ByteString.encodeUtf8("Hello"), contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(5);
    assertThat(bodyToHex(body)).isEqualTo("48656c6c6f");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo(
        "48656c6c6f");
  }

  @Test public void file() throws Exception {
    File file = File.createTempFile("RequestTest", "tmp");
    FileWriter writer = new FileWriter(file);
    writer.write("abc");
    writer.close();

    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = RequestBody.create(file, contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.contentLength()).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("616263");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo(
        "616263");
  }

  /** Common verbs used for apis such as GitHub, AWS, and Google Cloud. */
  @Test public void crudVerbs() throws IOException {
    MediaType contentType = MediaType.get("application/json");
    RequestBody body = RequestBody.create("{}", contentType);

    Request get = new Request.Builder().url("http://localhost/api").get().build();
    assertThat(get.method()).isEqualTo("GET");
    assertThat(get.body()).isNull();

    Request head = new Request.Builder().url("http://localhost/api").head().build();
    assertThat(head.method()).isEqualTo("HEAD");
    assertThat(head.body()).isNull();

    Request delete = new Request.Builder().url("http://localhost/api").delete().build();
    assertThat(delete.method()).isEqualTo("DELETE");
    assertThat(delete.body().contentLength()).isEqualTo(0L);

    Request post = new Request.Builder().url("http://localhost/api").post(body).build();
    assertThat(post.method()).isEqualTo("POST");
    assertThat(post.body()).isEqualTo(body);

    Request put = new Request.Builder().url("http://localhost/api").put(body).build();
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.body()).isEqualTo(body);

    Request patch = new Request.Builder().url("http://localhost/api").patch(body).build();
    assertThat(patch.method()).isEqualTo("PATCH");
    assertThat(patch.body()).isEqualTo(body);
  }

  @Test public void uninitializedURI() throws Exception {
    Request request = new Request.Builder().url("http://localhost/api").build();
    assertThat(request.url().uri()).isEqualTo(new URI("http://localhost/api"));
    assertThat(request.url()).isEqualTo(HttpUrl.get("http://localhost/api"));
  }

  @Test public void newBuilderUrlResetsUrl() {
    Request requestWithoutCache = new Request.Builder().url("http://localhost/api").build();
    Request builtRequestWithoutCache =
        requestWithoutCache.newBuilder().url("http://localhost/api/foo").build();
    assertThat(builtRequestWithoutCache.url()).isEqualTo(
        HttpUrl.get("http://localhost/api/foo"));

    Request requestWithCache = new Request.Builder().url("http://localhost/api").build();
    // cache url object
    requestWithCache.url();
    Request builtRequestWithCache = requestWithCache.newBuilder().url(
        "http://localhost/api/foo").build();
    assertThat(builtRequestWithCache.url()).isEqualTo(
        HttpUrl.get("http://localhost/api/foo"));
  }

  @Test public void cacheControl() {
    Request request = new Request.Builder()
        .cacheControl(new CacheControl.Builder().noCache().build())
        .url("https://square.com")
        .build();
    assertThat(request.headers("Cache-Control")).containsExactly("no-cache");
    assertThat(request.cacheControl().noCache()).isTrue();
  }

  @Test public void emptyCacheControlClearsAllCacheControlHeaders() {
    Request request = new Request.Builder()
        .header("Cache-Control", "foo")
        .cacheControl(new CacheControl.Builder().build())
        .url("https://square.com")
        .build();
    assertThat(request.headers("Cache-Control")).isEmpty();
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
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader(null, "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.header("Name", null);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader("Name", null);
      fail();
    } catch (IllegalArgumentException expected) {
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
    assertThat(request.tag()).isNull();
    assertThat(request.tag(Object.class)).isNull();
    assertThat(request.tag(UUID.class)).isNull();
    assertThat(request.tag(String.class)).isNull();
  }

  @Test public void defaultTag() {
    UUID tag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(tag)
        .build();
    assertThat(request.tag()).isSameAs(tag);
    assertThat(request.tag(Object.class)).isSameAs(tag);
    assertThat(request.tag(UUID.class)).isNull();
    assertThat(request.tag(String.class)).isNull();
  }

  @Test public void nullRemovesTag() {
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag("a")
        .tag(null)
        .build();
    assertThat(request.tag()).isNull();
  }

  @Test public void removeAbsentTag() {
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(null)
        .build();
    assertThat(request.tag()).isNull();
  }

  @Test public void objectTag() {
    UUID tag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(Object.class, tag)
        .build();
    assertThat(request.tag()).isSameAs(tag);
    assertThat(request.tag(Object.class)).isSameAs(tag);
    assertThat(request.tag(UUID.class)).isNull();
    assertThat(request.tag(String.class)).isNull();
  }

  @Test public void typedTag() {
    UUID uuidTag = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(UUID.class, uuidTag)
        .build();
    assertThat(request.tag()).isNull();
    assertThat(request.tag(Object.class)).isNull();
    assertThat(request.tag(UUID.class)).isSameAs(uuidTag);
    assertThat(request.tag(String.class)).isNull();
  }

  @Test public void replaceOnlyTag() {
    UUID uuidTag1 = UUID.randomUUID();
    UUID uuidTag2 = UUID.randomUUID();
    Request request = new Request.Builder()
        .url("https://square.com")
        .tag(UUID.class, uuidTag1)
        .tag(UUID.class, uuidTag2)
        .build();
    assertThat(request.tag(UUID.class)).isSameAs(uuidTag2);
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
    assertThat(request.tag()).isSameAs(objectTag);
    assertThat(request.tag(Object.class)).isSameAs(objectTag);
    assertThat(request.tag(UUID.class)).isSameAs(uuidTag);
    assertThat(request.tag(String.class)).isSameAs(stringTag);
    assertThat(request.tag(Long.class)).isSameAs(longTag);
  }

  /** Confirm that we don't accidentally share the backing map between objects. */
  @Test public void tagsAreImmutable() {
    Request.Builder builder = new Request.Builder()
        .url("https://square.com");
    Request requestA = builder.tag(String.class, "a").build();
    Request requestB = builder.tag(String.class, "b").build();
    Request requestC = requestA.newBuilder().tag(String.class, "c").build();
    assertThat(requestA.tag(String.class)).isSameAs("a");
    assertThat(requestB.tag(String.class)).isSameAs("b");
    assertThat(requestC.tag(String.class)).isSameAs("c");
  }

  private String bodyToHex(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    return buffer.readByteString().hex();
  }
}
