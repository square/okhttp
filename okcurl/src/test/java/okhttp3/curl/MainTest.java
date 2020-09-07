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
package okhttp3.curl;

import java.io.IOException;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

public class MainTest {
  public static Main fromArgs(String... args) {
    return CommandLine.populateCommand(new Main(), args);
  }

  @Test public void simple() {
    Request request = fromArgs("http://example.com").createRequest();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(request.body()).isNull();
  }

  @Test public void put() throws IOException {
    Request request = fromArgs("-X", "PUT", "-d", "foo", "http://example.com").createRequest();
    assertThat(request.method()).isEqualTo("PUT");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(request.body().contentLength()).isEqualTo(3);
  }

  @Test public void dataPost() {
    Request request = fromArgs("-d", "foo", "http://example.com").createRequest();
    RequestBody body = request.body();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(body.contentType().toString()).isEqualTo(
        "application/x-www-form-urlencoded; charset=utf-8");
    assertThat(bodyAsString(body)).isEqualTo("foo");
  }

  @Test public void dataPut() {
    Request request = fromArgs("-d", "foo", "-X", "PUT", "http://example.com").createRequest();
    RequestBody body = request.body();
    assertThat(request.method()).isEqualTo("PUT");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(body.contentType().toString()).isEqualTo(
        "application/x-www-form-urlencoded; charset=utf-8");
    assertThat(bodyAsString(body)).isEqualTo("foo");
  }

  @Test public void contentTypeHeader() {
    Request request = fromArgs("-d", "foo", "-H", "Content-Type: application/json",
        "http://example.com").createRequest();
    RequestBody body = request.body();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(body.contentType().toString()).isEqualTo("application/json; charset=utf-8");
    assertThat(bodyAsString(body)).isEqualTo("foo");
  }

  @Test public void referer() {
    Request request = fromArgs("-e", "foo", "http://example.com").createRequest();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(request.header("Referer")).isEqualTo("foo");
    assertThat(request.body()).isNull();
  }

  @Test public void userAgent() {
    Request request = fromArgs("-A", "foo", "http://example.com").createRequest();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.url().toString()).isEqualTo("http://example.com/");
    assertThat(request.header("User-Agent")).isEqualTo("foo");
    assertThat(request.body()).isNull();
  }

  @Test public void defaultUserAgent() {
    Request request = fromArgs("http://example.com").createRequest();
    assertThat(request.header("User-Agent")).startsWith("okcurl/");
  }

  @Test public void headerSplitWithDate() {
    Request request = fromArgs("-H", "If-Modified-Since: Mon, 18 Aug 2014 15:16:06 GMT",
        "http://example.com").createRequest();
    assertThat(request.header("If-Modified-Since")).isEqualTo(
        "Mon, 18 Aug 2014 15:16:06 GMT");
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
