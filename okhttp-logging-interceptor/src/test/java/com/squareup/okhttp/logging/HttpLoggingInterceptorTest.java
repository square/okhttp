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
package com.squareup.okhttp.logging;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.logging.HttpLoggingInterceptor.Level;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HttpLoggingInterceptorTest {
  private static final MediaType PLAIN = MediaType.parse("text/plain; charset=utf-8");

  @Rule public final MockWebServer server = new MockWebServer();

  private final OkHttpClient client = new OkHttpClient();
  private final List<String> logs = new ArrayList<>();
  private HttpLoggingInterceptor interceptor;

  @Before public void setUp() {
    HttpLoggingInterceptor.Logger logger = new HttpLoggingInterceptor.Logger() {
      @Override public void log(String message) {
        logs.add(message);
      }
    };
    interceptor = new HttpLoggingInterceptor(logger);
    client.networkInterceptors().add(interceptor);
    client.setConnectionPool(null);
  }

  @Test public void none() throws IOException {
    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();
    assertTrue(logs.isEmpty());
  }

  @Test public void basicGet() throws IOException {
    interceptor.setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();

    assertEquals(2, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms, 0-byte body\\)", logs.get(1)));
  }

  @Test public void basicPost() throws IOException {
    interceptor.setLevel(Level.BASIC);

    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create(PLAIN, "Hi?")).build()).execute();

    assertEquals(2, logs.size());
    assertEquals("--> POST / HTTP/1.1 (3-byte body)", logs.get(0));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms, 0-byte body\\)", logs.get(1)));
  }

  @Test public void basicResponseBody() throws IOException {
    interceptor.setLevel(Level.BASIC);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN.toString()));
    client.newCall(request().build()).execute();

    assertEquals(2, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms, 6-byte body\\)", logs.get(1)));
  }

  @Test public void headersGet() throws IOException {
    interceptor.setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();

    assertEquals(12, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(1));
    assertEquals("Connection: Keep-Alive", logs.get(2));
    assertEquals("Accept-Encoding: gzip", logs.get(3));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(4)));
    assertEquals("--> END GET", logs.get(5));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(6)));
    assertEquals("Content-Length: 0", logs.get(7));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(8));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(9)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(10)));
    assertEquals("<-- END HTTP", logs.get(11));
  }

  @Test public void headersPost() throws IOException {
    interceptor.setLevel(Level.HEADERS);

    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create(PLAIN, "Hi?")).build()).execute();

    assertEquals(14, logs.size());
    assertEquals("--> POST / HTTP/1.1", logs.get(0));
    assertEquals("Content-Type: text/plain; charset=utf-8", logs.get(1));
    assertEquals("Content-Length: 3", logs.get(2));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(3));
    assertEquals("Connection: Keep-Alive", logs.get(4));
    assertEquals("Accept-Encoding: gzip", logs.get(5));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(6)));
    assertEquals("--> END POST", logs.get(7));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(8)));
    assertEquals("Content-Length: 0", logs.get(9));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(10));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(11)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(12)));
    assertEquals("<-- END HTTP", logs.get(13));
  }

  @Test public void headersResponseBody() throws IOException {
    interceptor.setLevel(Level.HEADERS);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN.toString()));
    client.newCall(request().build()).execute();

    assertEquals(13, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(1));
    assertEquals("Connection: Keep-Alive", logs.get(2));
    assertEquals("Accept-Encoding: gzip", logs.get(3));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(4)));
    assertEquals("--> END GET", logs.get(5));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(6)));
    assertEquals("Content-Length: 6", logs.get(7));
    assertEquals("Content-Type: text/plain; charset=utf-8", logs.get(8));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(9));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(10)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(11)));
    assertEquals("<-- END HTTP", logs.get(12));
  }

  @Test public void bodyGet() throws IOException {
    interceptor.setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    client.newCall(request().build()).execute();

    assertEquals(12, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(1));
    assertEquals("Connection: Keep-Alive", logs.get(2));
    assertEquals("Accept-Encoding: gzip", logs.get(3));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(4)));
    assertEquals("--> END GET", logs.get(5));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(6)));
    assertEquals("Content-Length: 0", logs.get(7));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(8));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(9)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(10)));
    assertEquals("<-- END HTTP (0-byte body)", logs.get(11));
  }

  @Test public void bodyPost() throws IOException {
    interceptor.setLevel(Level.BODY);

    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create(PLAIN, "Hi?")).build()).execute();

    assertEquals(16, logs.size());
    assertEquals("--> POST / HTTP/1.1", logs.get(0));
    assertEquals("Content-Type: text/plain; charset=utf-8", logs.get(1));
    assertEquals("Content-Length: 3", logs.get(2));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(3));
    assertEquals("Connection: Keep-Alive", logs.get(4));
    assertEquals("Accept-Encoding: gzip", logs.get(5));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(6)));
    assertEquals("", logs.get(7));
    assertEquals("Hi?", logs.get(8));
    assertEquals("--> END POST (3-byte body)", logs.get(9));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(10)));
    assertEquals("Content-Length: 0", logs.get(11));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(12));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(13)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(14)));
    assertEquals("<-- END HTTP (0-byte body)", logs.get(15));
  }

  @Test public void bodyResponseBody() throws IOException {
    interceptor.setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setBody("Hello!")
        .setHeader("Content-Type", PLAIN.toString()));
    client.newCall(request().build()).execute();

    assertEquals(15, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(1));
    assertEquals("Connection: Keep-Alive", logs.get(2));
    assertEquals("Accept-Encoding: gzip", logs.get(3));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(4)));
    assertEquals("--> END GET", logs.get(5));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(6)));
    assertEquals("Content-Length: 6", logs.get(7));
    assertEquals("Content-Type: text/plain; charset=utf-8", logs.get(8));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(9));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(10)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(11)));
    assertEquals("", logs.get(12));
    assertEquals("Hello!", logs.get(13));
    assertEquals("<-- END HTTP (6-byte body)", logs.get(14));
  }

  @Test public void bodyResponseBodyChunked() throws IOException {
    interceptor.setLevel(Level.BODY);

    server.enqueue(new MockResponse()
        .setChunkedBody("Hello!", 2)
        .setHeader("Content-Type", PLAIN.toString()));
    client.newCall(request().build()).execute();

    assertEquals(15, logs.size());
    assertEquals("--> GET / HTTP/1.1", logs.get(0));
    assertEquals("Host: " + server.getHostName() + ":" + server.getPort(), logs.get(1));
    assertEquals("Connection: Keep-Alive", logs.get(2));
    assertEquals("Accept-Encoding: gzip", logs.get(3));
    assertTrue(Pattern.matches("User-Agent: okhttp/.+", logs.get(4)));
    assertEquals("--> END GET", logs.get(5));
    assertTrue(Pattern.matches("<-- HTTP/1\\.1 200 OK \\(\\d+ms\\)", logs.get(6)));
    assertEquals("Transfer-encoding: chunked", logs.get(7));
    assertEquals("Content-Type: text/plain; charset=utf-8", logs.get(8));
    assertEquals("OkHttp-Selected-Protocol: http/1.1", logs.get(9));
    assertTrue(Pattern.matches("OkHttp-Sent-Millis: \\d+", logs.get(10)));
    assertTrue(Pattern.matches("OkHttp-Received-Millis: \\d+", logs.get(11)));
    assertEquals("", logs.get(12));
    assertEquals("Hello!", logs.get(13));
    assertEquals("<-- END HTTP (6-byte body)", logs.get(14));
  }

  private Request.Builder request() {
    return new Request.Builder().url(server.url("/"));
  }
}
