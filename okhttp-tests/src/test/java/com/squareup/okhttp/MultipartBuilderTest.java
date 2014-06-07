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

import okio.Buffer;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class MultipartBuilderTest {
  @Test(expected = IllegalStateException.class)
  public void onePartRequired() throws Exception {
    new MultipartBuilder().build();
  }

  @Test public void singlePart() throws Exception {
    String expected = ""
        + "--123\r\n"
        + "Content-Length: 13\r\n"
        + "\r\n"
        + "Hello, World!\r\n"
        + "--123--";

    RequestBody requestBody = new MultipartBuilder("123")
        .addPart(RequestBody.create(null, "Hello, World!"))
        .build();

    assertEquals("multipart/mixed; boundary=123", requestBody.contentType().toString());

    Buffer buffer = new Buffer();
    requestBody.writeTo(buffer);
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
        + "--123--";

    RequestBody requestBody = new MultipartBuilder("123")
        .addPart(RequestBody.create(null, "Quick"))
        .addPart(RequestBody.create(null, "Brown"))
        .addPart(RequestBody.create(null, "Fox"))
        .build();

    assertEquals("multipart/mixed; boundary=123", requestBody.contentType().toString());

    Buffer buffer = new Buffer();
    requestBody.writeTo(buffer);
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
        + "--AaB03x--";

    RequestBody requestBody = new MultipartBuilder("AaB03x")
        .type(MultipartBuilder.FORM)
        .addPart(
            headers("Content-Disposition", "form-data; name=\"submit-name\""),
            RequestBody.create(null, "Larry"))
        .addPart(
            headers("Content-Disposition", "form-data; name=\"files\""),
            new MultipartBuilder("BbC04y")
                .addPart(
                    headers("Content-Disposition", "file; filename=\"file1.txt\""),
                    RequestBody.create(
                        MediaType.parse("text/plain"), "... contents of file1.txt ..."))
                .addPart(
                    headers(
                        "Content-Disposition", "file; filename=\"file2.gif\"",
                        "Content-Transfer-Encoding", "binary"),
                    RequestBody.create(
                        MediaType.parse("image/gif"),
                        "... contents of file2.gif ...".getBytes(UTF_8)))
                .build())
        .build();

    assertEquals("multipart/form-data; boundary=AaB03x", requestBody.contentType().toString());

    Buffer buffer = new Buffer();
    requestBody.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void contentTypeHeaderIsForbidden() throws Exception {
    try {
      new MultipartBuilder()
          .addPart(headers("Content-Type", "text/plain"), RequestBody.create(null, "Hello, World!"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void contentLengthHeaderIsForbidden() throws Exception {
    try {
      new MultipartBuilder()
          .addPart(headers("Content-Length", "13"), RequestBody.create(null, "Hello, World!"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private static Headers headers(String name, String value) {
    return new Headers.Builder().add(name, value).build();
  }

  private static Headers headers(String name1, String value1, String name2, String value2) {
    return new Headers.Builder()
        .add(name1, value1)
        .add(name2, value2)
        .build();
  }
}
