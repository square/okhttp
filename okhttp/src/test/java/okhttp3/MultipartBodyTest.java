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
package okhttp3;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSink;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class MultipartBodyTest {
  @Test public void onePartRequired() throws Exception {
    try {
      new MultipartBody.Builder().build();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Multipart body must have at least one part.");
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
        .addPart(RequestBody.create("Hello, World!", null))
        .build();

    assertThat(body.boundary()).isEqualTo("123");
    assertThat(body.type()).isEqualTo(MultipartBody.MIXED);
    assertThat(body.contentType().toString()).isEqualTo("multipart/mixed; boundary=123");
    assertThat(body.parts().size()).isEqualTo(1);
    assertThat(body.contentLength()).isEqualTo(53);

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(body.contentLength()).isEqualTo(buffer.size());
    assertThat(buffer.readUtf8()).isEqualTo(expected);
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
        .addPart(RequestBody.create("Quick", null))
        .addPart(RequestBody.create("Brown", null))
        .addPart(RequestBody.create("Fox", null))
        .build();

    assertThat(body.boundary()).isEqualTo("123");
    assertThat(body.type()).isEqualTo(MultipartBody.MIXED);
    assertThat(body.contentType().toString()).isEqualTo("multipart/mixed; boundary=123");
    assertThat(body.parts().size()).isEqualTo(3);
    assertThat(body.contentLength()).isEqualTo(112);

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(body.contentLength()).isEqualTo(buffer.size());
    assertThat(buffer.readUtf8()).isEqualTo(expected);
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
                        "... contents of file1.txt ...", MediaType.get("text/plain")))
                .addPart(
                    Headers.of(
                        "Content-Disposition", "file; filename=\"file2.gif\"",
                        "Content-Transfer-Encoding", "binary"),
                    RequestBody.create(
                        "... contents of file2.gif ...".getBytes(UTF_8),
                        MediaType.get("image/gif")))
                .build())
        .build();

    assertThat(body.boundary()).isEqualTo("AaB03x");
    assertThat(body.type()).isEqualTo(MultipartBody.FORM);
    assertThat(body.contentType().toString()).isEqualTo(
        "multipart/form-data; boundary=AaB03x");
    assertThat(body.parts().size()).isEqualTo(2);
    assertThat(body.contentLength()).isEqualTo(568);

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(body.contentLength()).isEqualTo(buffer.size());
    assertThat(buffer.readUtf8()).isEqualTo(expected);
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
        + "Content-Disposition: form-data; name=\"field with \u007e\"\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Alpha\r\n"
        + "--AaB03x--\r\n";

    MultipartBody body = new MultipartBody.Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart("field with spaces", "filename with spaces.txt",
            RequestBody.create("okay", MediaType.get("text/plain; charset=utf-8")))
        .addFormDataPart("field with \"", "\"")
        .addFormDataPart("field with %22", "%22")
        .addFormDataPart("field with \u007e", "Alpha")
        .build();

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
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
        .addPart(RequestBody.create("Quick", null))
        .addPart(new StreamingBody("Brown"))
        .addPart(RequestBody.create("Fox", null))
        .build();

    assertThat(body.boundary()).isEqualTo("123");
    assertThat(body.type()).isEqualTo(MultipartBody.MIXED);
    assertThat(body.contentType().toString()).isEqualTo("multipart/mixed; boundary=123");
    assertThat(body.parts().size()).isEqualTo(3);
    assertThat(body.contentLength()).isEqualTo(-1);

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
  }

  @Test public void contentTypeHeaderIsForbidden() throws Exception {
    MultipartBody.Builder multipart = new MultipartBody.Builder();
    try {
      multipart.addPart(Headers.of("Content-Type", "text/plain"),
          RequestBody.create("Hello, World!", null));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void contentLengthHeaderIsForbidden() throws Exception {
    MultipartBody.Builder multipart = new MultipartBody.Builder();
    try {
      multipart.addPart(Headers.of("Content-Length", "13"),
          RequestBody.create("Hello, World!", null));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void partAccessors() throws IOException {
    MultipartBody body = new MultipartBody.Builder()
        .addPart(Headers.of("Foo", "Bar"), RequestBody.create("Baz", null))
        .build();
    assertThat(body.parts().size()).isEqualTo(1);

    Buffer part1Buffer = new Buffer();
    MultipartBody.Part part1 = body.part(0);
    part1.body().writeTo(part1Buffer);
    assertThat(part1.headers()).isEqualTo(Headers.of("Foo", "Bar"));
    assertThat(part1Buffer.readUtf8()).isEqualTo("Baz");
  }

  @Test public void nonAsciiFilename() throws Exception {
    String expected = ""
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"attachment\"; filename=\"resumé.pdf\"\r\n"
        + "Content-Type: application/pdf; charset=utf-8\r\n"
        + "Content-Length: 17\r\n"
        + "\r\n"
        + "Jesse’s Resumé\r\n"
        + "--AaB03x--\r\n";

    MultipartBody body = new MultipartBody.Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart("attachment", "resumé.pdf",
            RequestBody.create("Jesse’s Resumé", MediaType.parse("application/pdf")))
        .build();

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
  }
}
