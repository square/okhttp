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
package com.squareup.okhttp;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSink;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class MultipartBodyTest {
  @Test public void onePartRequired() throws Exception {
    try {
      new MultipartBody.Builder().build();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Multipart body must have at least one part.", e.getMessage());
    }
  }

  @Test public void singlePart() throws Exception {
    String expected = ""
        + "--123\r\n"
        + "Content-Length: 13\r\n"
        + "\r\n"
        + "Hello, World!\r\n"
        + "--123--\r\n";

    MultipartBody body = new MultipartBody.Builder("123")
        .addPart(RequestBody.create(null, "Hello, World!"))
        .build();

    assertEquals("123", body.boundary());
    assertEquals(MultipartBody.MIXED, body.type());
    assertEquals("multipart/mixed; boundary=123", body.contentType().toString());
    assertEquals(1, body.parts().size());
    assertEquals(53, body.contentLength());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertEquals(buffer.size(), body.contentLength());
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void threeParts() throws Exception {
    String expected = ""
        + "--123\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Quick\r\n"
        + "--123\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Brown\r\n"
        + "--123\r\n"
        + "Content-Length: 3\r\n"
        + "\r\n"
        + "Fox\r\n"
        + "--123--\r\n";

    MultipartBody body = new MultipartBody.Builder("123")
        .addPart(RequestBody.create(null, "Quick"))
        .addPart(RequestBody.create(null, "Brown"))
        .addPart(RequestBody.create(null, "Fox"))
        .build();

    assertEquals("123", body.boundary());
    assertEquals(MultipartBody.MIXED, body.type());
    assertEquals("multipart/mixed; boundary=123", body.contentType().toString());
    assertEquals(3, body.parts().size());
    assertEquals(112, body.contentLength());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertEquals(buffer.size(), body.contentLength());
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void fieldAndTwoFiles() throws Exception {
    String expected = ""
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"submit-name\"\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Larry\r\n"
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"files\"\r\n"
        + "Content-Type: multipart/mixed; boundary=BbC04y\r\n"
        + "Content-Length: 337\r\n"
        + "\r\n"
        + "--BbC04y\r\n"
        + "Content-Disposition: file; filename=\"file1.txt\"\r\n"
        + "Content-Type: text/plain; charset=utf-8\r\n"
        + "Content-Length: 29\r\n"
        + "\r\n"
        + "... contents of file1.txt ...\r\n"
        + "--BbC04y\r\n"
        + "Content-Disposition: file; filename=\"file2.gif\"\r\n"
        + "Content-Transfer-Encoding: binary\r\n"
        + "Content-Type: image/gif\r\n"
        + "Content-Length: 29\r\n"
        + "\r\n"
        + "... contents of file2.gif ...\r\n"
        + "--BbC04y--\r\n"
        + "\r\n"
        + "--AaB03x--\r\n";

    MultipartBody body = new MultipartBody.Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart("submit-name", "Larry")
        .addFormDataPart("files", null,
            new MultipartBody.Builder("BbC04y")
                .addPart(
                    Headers.of("Content-Disposition", "file; filename=\"file1.txt\""),
                    RequestBody.create(
                        MediaType.parse("text/plain"), "... contents of file1.txt ..."))
                .addPart(
                    Headers.of(
                        "Content-Disposition", "file; filename=\"file2.gif\"",
                        "Content-Transfer-Encoding", "binary"),
                    RequestBody.create(
                        MediaType.parse("image/gif"),
                        "... contents of file2.gif ...".getBytes(UTF_8)))
                .build())
        .build();

    assertEquals("AaB03x", body.boundary());
    assertEquals(MultipartBody.FORM, body.type());
    assertEquals("multipart/form-data; boundary=AaB03x", body.contentType().toString());
    assertEquals(2, body.parts().size());
    assertEquals(568, body.contentLength());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertEquals(buffer.size(), body.contentLength());
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void stringEscapingIsWeird() throws Exception {
    String expected = ""
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"field with spaces\"; filename=\"filename with spaces.txt\"\r\n"
        + "Content-Type: text/plain; charset=utf-8\r\n"
        + "Content-Length: 4\r\n"
        + "\r\n"
        + "okay\r\n"
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"field with %22\"\r\n"
        + "Content-Length: 1\r\n"
        + "\r\n"
        + "\"\r\n"
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"field with %22\"\r\n"
        + "Content-Length: 3\r\n"
        + "\r\n"
        + "%22\r\n"
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"field with \u0391\"\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Alpha\r\n"
        + "--AaB03x--\r\n";

    MultipartBody body = new MultipartBody.Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart("field with spaces", "filename with spaces.txt",
            RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), "okay"))
        .addFormDataPart("field with \"", "\"")
        .addFormDataPart("field with %22", "%22")
        .addFormDataPart("field with \u0391", "Alpha")
        .build();

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void streamingPartHasNoLength() throws Exception {
    class StreamingBody extends RequestBody {
      private final String body;

      StreamingBody(String body) {
        this.body = body;
      }

      @Override public MediaType contentType() {
        return null;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8(body);
      }
    }

    String expected = ""
        + "--123\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Quick\r\n"
        + "--123\r\n"
        + "\r\n"
        + "Brown\r\n"
        + "--123\r\n"
        + "Content-Length: 3\r\n"
        + "\r\n"
        + "Fox\r\n"
        + "--123--\r\n";

    MultipartBody body = new MultipartBody.Builder("123")
        .addPart(RequestBody.create(null, "Quick"))
        .addPart(new StreamingBody("Brown"))
        .addPart(RequestBody.create(null, "Fox"))
        .build();

    assertEquals("123", body.boundary());
    assertEquals(MultipartBody.MIXED, body.type());
    assertEquals("multipart/mixed; boundary=123", body.contentType().toString());
    assertEquals(3, body.parts().size());
    assertEquals(-1, body.contentLength());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void contentTypeHeaderIsForbidden() throws Exception {
    MultipartBody.Builder multipart = new MultipartBody.Builder();
    try {
      multipart.addPart(Headers.of("Content-Type", "text/plain"),
          RequestBody.create(null, "Hello, World!"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void contentLengthHeaderIsForbidden() throws Exception {
    MultipartBody.Builder multipart = new MultipartBody.Builder();
    try {
      multipart.addPart(Headers.of("Content-Length", "13"),
          RequestBody.create(null, "Hello, World!"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
