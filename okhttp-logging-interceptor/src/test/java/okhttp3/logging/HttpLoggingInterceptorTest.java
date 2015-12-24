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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor.Level;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HttpLoggingInterceptorTest {
  private static final MediaType PLAIN = MediaType.parse("text/plain; charset=utf-8");

  @Rule public final MockWebServer server = new MockWebServer();

  private final OkHttpClient client = new OkHttpClient();
  private String host;
  private HttpUrl url;

  private final LogRecorder networkLogs = new LogRecorder();
  private final HttpLoggingInterceptor networkInterceptor =
      new HttpLoggingInterceptor(networkLogs);

  private final LogRecorder applicationLogs = new LogRecorder();
  private final HttpLoggingInterceptor applicationInterceptor =
      new HttpLoggingInterceptor(applicationLogs);

  private void setLevel(Level level) {
    networkInterceptor.setLevel(level);
    applicationInterceptor.setLevel(level);
  }

  @Before public void setUp() {
    client.networkInterceptors().add(networkInterceptor);
    client.interceptors().add(applicationInterceptor);
    client.setConnectionPool(null);

    host = server.getHostName() + ":" + server.getPort();
    url = server.url("/");
  }

  @Test public void levelGetter() {
    // The default is NONE.
    Assert.assertEquals(Level.NONE, applicationInterceptor.getLevel());

    for (Level level : Level.values()) {
      applicationInterceptor.setLevel(level);
      assertEquals(level, applicationInterceptor.getLevel());
    }
  }

  @Test public void setLevelShouldPreventNullValue() {
    try {
      applicationInterceptor.setLevel(null);
      fail();
    } catch (NullPointerException expected) {
      assertEquals("level == null. Use Level.NONE instead.", expected.getMessage());
    }
  }

  @Test public void setLevelShouldReturnSameInstanceOfInterceptor() {
    for (Level level : Level.values()) {
      assertSame(applicationInterceptor, applicationInterceptor.setLevel(level));
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test public void basicPost() throws IOException {
    setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create(PLAIN, "Hi?")).build()).execute();

    applicationLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1 (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 0-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1 (3-byte body)")
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 6-byte body\\)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms, 6-byte body\\)")
        .assertNoMoreLogs();
  }

  @Test public void headersGet() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPost() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create(PLAIN, "Hi?")).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void headersPostNoContentType() throws IOException {
    setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create(null, "Hi?")).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
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
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Transfer-Encoding: chunked")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END POST")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP")
        .assertNoMoreLogs();
  }

  @Test public void bodyGet() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- " + code + " No Content " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- " + code + " No Content " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyPost() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    Request request = request().post(RequestBody.create(PLAIN, "Hi?")).build();
    Response response = client.newCall(request).execute();
    response.body().close();

    applicationLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogEqual("Content-Length: 3")
        .assertLogEqual("")
        .assertLogEqual("Hi?")
        .assertLogEqual("--> END POST (3-byte body)")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 0")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP (0-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> POST " + url + " HTTP/1.1")
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
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Length: 6")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
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
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Transfer-encoding: chunked")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Transfer-encoding: chunked")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Hello!")
        .assertLogEqual("<-- END HTTP (6-byte body)")
        .assertNoMoreLogs();
  }

  @Test public void bodyResponseNotIdentityEncoded() throws IOException {
    setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", PLAIN)
        .setBody(new Buffer().write(ByteString.decodeBase64(
            "H4sIAAAAAAAAAPNIzcnJ11HwQKIAdyO+9hMAAAA="))));
    Response response = client.newCall(request().build()).execute();
    response.body().close();

    networkLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("Host: " + host)
        .assertLogEqual("Connection: Keep-Alive")
        .assertLogEqual("Accept-Encoding: gzip")
        .assertLogMatch("User-Agent: okhttp/.+")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Encoding: gzip")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("Content-Length: \\d+")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("<-- END HTTP (encoded body omitted)")
        .assertNoMoreLogs();

    applicationLogs
        .assertLogEqual("--> GET " + url + " HTTP/1.1")
        .assertLogEqual("--> END GET")
        .assertLogMatch("<-- 200 OK " + url + " \\(\\d+ms\\)")
        .assertLogEqual("Content-Type: text/plain; charset=utf-8")
        .assertLogMatch("OkHttp-Sent-Millis: \\d+")
        .assertLogMatch("OkHttp-Received-Millis: \\d+")
        .assertLogEqual("")
        .assertLogEqual("Hello, Hello, Hello")
        .assertLogEqual("<-- END HTTP (19-byte body)")
        .assertNoMoreLogs();
  }

  private Request.Builder request() {
    return new Request.Builder().url(url);
  }

  private static class LogRecorder implements HttpLoggingInterceptor.Logger {
    private final List<String> logs = new ArrayList<>();
    private int index;

    LogRecorder assertLogEqual(String expected) {
      assertTrue("No more messages found", index < logs.size());
      String actual = logs.get(index++);
      assertEquals(expected, actual);
      return this;
    }

    LogRecorder assertLogMatch(String pattern) {
      assertTrue("No more messages found", index < logs.size());
      String actual = logs.get(index++);
      assertTrue("<" + actual + "> did not match pattern <" + pattern + ">",
          Pattern.matches(pattern, actual));
      return this;
    }

    void assertNoMoreLogs() {
      assertTrue("More messages remain: " + logs.subList(index, logs.size()), index == logs.size());
    }

    @Override public void log(String message) {
      logs.add(message);
    }
  }
}
