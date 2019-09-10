/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.logging;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.testing.PlatformRule;
import okhttp3.Protocol;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor.Level;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

public final class HttpLoggingInterceptorTest {
  private static final MediaType PLAIN = MediaType.get("text/plain; charset=utf-8");

  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final MockWebServer server = new MockWebServer();

  private final HandshakeCertificates handshakeCertificates = localhost();
  private final HostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
  private OkHttpClient client;
  private String host;
  private HttpUrl url;

  private final LogRecorder networkLogs = new LogRecorder();
  private final HttpLoggingInterceptor networkInterceptor =
      new HttpLoggingInterceptor(networkLogs);

  private final LogRecorder applicationLogs = new LogRecorder();
  private final HttpLoggingInterceptor applicationInterceptor =
      new HttpLoggingInterceptor(applicationLogs);

  private Interceptor extraNetworkInterceptor = null;

  private void setLevel(Level level) {
    networkInterceptor.setLevel(level);
    applicationInterceptor.setLevel(level);
  }

  @Before public void setUp() {
    client = new OkHttpClient.Builder()
        .addNetworkInterceptor(chain -> extraNetworkInterceptor != null
            ? extraNetworkInterceptor.intercept(chain)
            : chain.proceed(chain.request()))
        .addNetworkInterceptor(networkInterceptor)
        .addInterceptor(applicationInterceptor)
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(hostnameVerifier)
        .build();

    host = server.getHostName() + ":" + server.getPort();
    url = server.url("/");
  }

  @Test public void levelGetter() {
    // The default is NONE.
    assertThat(applicationInterceptor.getLevel()).isEqualTo(Level.NONE);

    for (Level level : Level.values()) {
      applicationInterceptor.setLevel(level);
      assertThat(applicationInterceptor.getLevel()).isEqualTo(level);
    }
  }

  @Test public void setLevelShouldPreventNullValue() {
    try {
      applicationInterceptor.setLevel(null);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setLevelShouldReturnSameInstanceOfInterceptor() {
    for (Level level : Level.values()) {
      assertThat(applicationInterceptor.setLevel(level)).isSameAs(applicationInterceptor);
    }
  }

  @Test public void none() throws IOException {
    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();

    applicationLogs.assertNoMoreLogs();
    networkLogs.assertNoMoreLogs();
  }

  @Test public void basicGet() throws IOException {
    setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test public void basicPost() throws IOException {
    setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create("Hi?", PLAIN)).build()).execute();

    applicationLogs
        .assertLogEqual("--> POST " + url + " (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1 (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test public void basicResponseBody() throws IOException {
    setLevel(Level.BASIC);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 6-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 6-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test public void basicChunkedResponseBody() throws IOException {
    setLevel(Level.BASIC);

    server.enqueue(new MockResponse()
        .setChunkedBody("Hello!", 2)
        .setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, unknown-length body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, unknown-length body\\)")
        .assertNoMoreLogs();
  }

  @Test public void headersGet() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPost() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create("Hi?", PLAIN)).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPostNoContentType() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create("Hi?", null)).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPostNoLength() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    RequestBody body = new RequestBody() {
      @Override public MediaType contentType() {
        return PLAIN;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("Hi!");
      }
    };
    Response response = client.newCall(request().post(body).build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Transfer-Encoding: chunked")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPostWithHeaderOverrides() throws IOException {
    setLevel(Level.HEADERS);

    extraNetworkInterceptor = chain -> chain.proceed(chain.request()
        .newBuilder()
        .header("Content-Length", "2")
        .header("Content-Type", "text/plain-ish")
        .build());

    server.enqueue(new MockResponse());
    client.newCall(request()
        .post(RequestBody.create("Hi?", PLAIN))
        .build()).execute();

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("Content-Length: 2")
        .assertLogEqual("Content-Type: text/plain-ish")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersResponseBody() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void bodyGet() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyGet204() throws IOException {
    setLevel(Level.BODY);
    bodyGetNoBody(204);
  }

  @Test public void bodyGet205() throws IOException {
    setLevel(Level.BODY);
    bodyGetNoBody(205);
  }

  private void bodyGetNoBody(int code) throws IOException {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 " + code + " No Content"));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- " + code + " No Content " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- " + code + " No Content " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyPost() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create("Hi?", PLAIN)).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("")
        .assertLogEqual("Hi?")
        .assertLogEqual("--> END POST (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " http/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("")
        .assertLogEqual("Hi?")
        .assertLogEqual("--> END POST (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyResponseBody() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyResponseBodyChunked() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setChunkedBody("Hello!", 2)
        .setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Transfer-encoding: chunked")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Transfer-encoding: chunked")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyResponseGzipEncoded() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", PLAIN)
        .setBody(new Buffer().write(ByteString.decodeBase64(
            "H4sIAAAAAAAAAPNIzcnJ11HwQKIAdyO+9hMAAAA="))));
    Response response = client.newCall(request().build()).execute();

    ResponseBody responseBody = response.body();
    assertThat(responseBody.string()).overridingErrorMessage(
        "Expected response body to be valid").isEqualTo("Hello, Hello, Hello");
    responseBody.close();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Encoding: gzip")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("Content-Length: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Hello, Hello, Hello")
        .assertLogEqual("<-- END HTTP (19-byte, 29-gzipped-byte body)")
        .assertNoMoreLogs();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("Hello, Hello, Hello")
        .assertLogEqual("<-- END HTTP (19-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyResponseUnknownEncoded() throws IOException {
      setLevel(Level.BODY);

      server.enqueue(new MockResponse()
          // It's invalid to return this if not requested, but the server might anyway
          .setHeader("Content-Encoding", "br")
          .setHeader("Content-Type", PLAIN)
          .setBody(new Buffer().write(ByteString.decodeBase64(
              "iwmASGVsbG8sIEhlbGxvLCBIZWxsbwoD"))));
      Response response = client.newCall(request().build()).execute();
      response.body().close();

      networkLogs
          .assertLogEqual("--> GET " + url + " http/1.1")
          .assertLogEqual("Host: " + host)
          .assertLogEqual("Connection: Keep-Alive")
          .assertLogEqual("Accept-Encoding: gzip")
          .assertLogMatch("User-Agent: okhttp/.+")
          .assertLogEqual("--> END GET")
          .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
          .assertLogEqual("Content-Encoding: br")
          .assertLogEqual("Content-Type: text/plain; charset=utf-8")
          .assertLogMatch("Content-Length: \\d+")
          .assertLogEqual("<-- END HTTP (encoded body omitted)")
          .assertNoMoreLogs();

      applicationLogs
          .assertLogEqual("--> GET " + url)
          .assertLogEqual("--> END GET")
          .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
          .assertLogEqual("Content-Encoding: br")
          .assertLogEqual("Content-Type: text/plain; charset=utf-8")
          .assertLogMatch("Content-Length: \\d+")
          .assertLogEqual("<-- END HTTP (encoded body omitted)")
          .assertNoMoreLogs();
    }

  @Test public void bodyGetMalformedCharset() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "text/html; charset=0")
        .setBody("Body with unknown charset"));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Type: text/html; charset=0")
        .assertLogMatch("Content-Length: \\d+")
        .assertLogMatch("")
        .assertLogEqual("Body with unknown charset")
        .assertLogEqual("<-- END HTTP (25-byte body)")
        .assertNoMoreLogs();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Type: text/html; charset=0")
        .assertLogMatch("Content-Length: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Body with unknown charset")
        .assertLogEqual("<-- END HTTP (25-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void responseBodyIsBinary() throws IOException {
    setLevel(Level.BODY);
    Buffer buffer = new Buffer();
    buffer.writeUtf8CodePoint(0x89);
    buffer.writeUtf8CodePoint(0x50);
    buffer.writeUtf8CodePoint(0x4e);
    buffer.writeUtf8CodePoint(0x47);
    buffer.writeUtf8CodePoint(0x0d);
    buffer.writeUtf8CodePoint(0x0a);
    buffer.writeUtf8CodePoint(0x1a);
    buffer.writeUtf8CodePoint(0x0a);
    server.enqueue(new MockResponse()
        .setBody(buffer)
        .setHeader("Content-Type", "image/png; charset=utf-8"));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 9")
        .assertLogEqual("Content-Type: image/png; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("<-- END HTTP (binary 9-byte body omitted)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 9")
        .assertLogEqual("Content-Type: image/png; charset=utf-8")
        .assertLogEqual("")
        .assertLogEqual("<-- END HTTP (binary 9-byte body omitted)")
        .assertNoMoreLogs();
  }

  @Test public void connectFail() throws IOException {
    setLevel(Level.BASIC);
    client = new OkHttpClient.Builder()
        .dns(hostname -> { throw new UnknownHostException("reason"); })
        .addInterceptor(applicationInterceptor)
        .build();

    try {
      client.newCall(request().build()).execute();
      fail();
    } catch (UnknownHostException expected) {
    }

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("<-- HTTP FAILED: java.net.UnknownHostException: reason")
        .assertNoMoreLogs();
  }

  @Test public void http2() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    url = server.url("/");

    setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    assumeThat(response.protocol(), equalTo(Protocol.HTTP_2));

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogMatch("<-- 200 " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " h2")
        .assertLogMatch("<-- 200 " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test
  public void headersAreRedacted() throws Exception {
    HttpLoggingInterceptor networkInterceptor =
        new HttpLoggingInterceptor(networkLogs).setLevel(Level.HEADERS);
    networkInterceptor.redactHeader("sEnSiTiVe");

    HttpLoggingInterceptor applicationInterceptor =
        new HttpLoggingInterceptor(applicationLogs).setLevel(Level.HEADERS);
    applicationInterceptor.redactHeader("sEnSiTiVe");

    client =
        new OkHttpClient.Builder()
            .addNetworkInterceptor(networkInterceptor)
            .addInterceptor(applicationInterceptor)
            .build();

    server.enqueue(
        new MockResponse().addHeader("SeNsItIvE", "Value").addHeader("Not-Sensitive", "Value"));
    Response response =
        client
            .newCall(
                request()
                    .addHeader("SeNsItIvE", "Value")
                    .addHeader("Not-Sensitive", "Value")
                    .build())
            .execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url)
        .assertLogEqual("SeNsItIvE: ██")
        .assertLogEqual("Not-Sensitive: Value")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("SeNsItIvE: ██")
        .assertLogEqual("Not-Sensitive: Value")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " http/1.1")
        .assertLogEqual("SeNsItIvE: ██")
        .assertLogEqual("Not-Sensitive: Value")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogEqual("SeNsItIvE: ██")
        .assertLogEqual("Not-Sensitive: Value")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void duplexRequestsAreNotLogged() throws Exception {
    platform.assumeHttp2Support();

    server.useHttps(handshakeCertificates.sslSocketFactory(), false); // HTTP/2
    url = server.url("/");

    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setBody("Hello response!"));

    RequestBody asyncRequestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("Hello request!");
        sink.close();
      }

      @Override public boolean isDuplex() {
        return true;
      }
    };

    Request request = request()
        .post(asyncRequestBody)
        .build();
    Response response = client.newCall(request).execute();
    assumeThat(response.protocol(), equalTo(Protocol.HTTP_2));

    assertThat(response.body().string()).isEqualTo("Hello response!");

    applicationLogs
        .assertLogEqual("--> POST " + url)
        .assertLogEqual("--> END POST (duplex request body omitted)")
        .assertLogMatch("<-- 200 " + url + " \\(\\d+ms\\)")
        .assertLogEqual("content-length: 15")
        .assertLogEqual("")
        .assertLogEqual("Hello response!")
        .assertLogEqual("<-- END HTTP (15-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void oneShotRequestsAreNotLogged() throws Exception {
    url = server.url("/");

    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
                           .setBody("Hello response!"));

    RequestBody asyncRequestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      int counter = 0;
      @Override public void writeTo(BufferedSink sink) throws IOException {
        counter++;
        assertThat(counter).isLessThanOrEqualTo(1);

        sink.writeUtf8("Hello request!");
        sink.close();
      }

      @Override public boolean isOneShot() {
        return true;
      }
    };

    Request request = request()
                              .post(asyncRequestBody)
                              .build();
    Response response = client.newCall(request).execute();

    assertThat(response.body().string()).isEqualTo("Hello response!");

    applicationLogs
            .assertLogEqual("--> POST " + url)
            .assertLogEqual("--> END POST (one-shot body omitted)")
            .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
            .assertLogEqual("Content-Length: 15")
            .assertLogEqual("")
            .assertLogEqual("Hello response!")
            .assertLogEqual("<-- END HTTP (15-byte body)")
            .assertNoMoreLogs();
  }

  private Request.Builder request() {
    return new Request.Builder().url(url);
  }

  static class LogRecorder implements HttpLoggingInterceptor.Logger {
    private final List<String> logs = new ArrayList<>();
    private int index;

    LogRecorder assertLogEqual(String expected) {
      assertThat(index).overridingErrorMessage("No more messages found").isLessThan(logs.size());
      String actual = logs.get(index++);
      assertThat(actual).isEqualTo(expected);
      return this;
    }

    LogRecorder assertLogMatch(String pattern) {
      assertThat(index).overridingErrorMessage("No more messages found").isLessThan(logs.size());
      String actual = logs.get(index++);
      assertThat(actual).matches(Pattern.compile(pattern, Pattern.DOTALL));
      return this;
    }

    void assertNoMoreLogs() {
      assertThat(logs.size()).overridingErrorMessage(
          "More messages remain: " + logs.subList(index, logs.size())).isEqualTo(index);
    }

    @Override public void log(String message) {
      logs.add(message);
    }
  }
}
