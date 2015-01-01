/*
 * Copyright (C) 2011 Google Inc.
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
package com.squareup.okhttp.mockwebserver;

import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Rule;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MockWebServerTest {
  @Rule public final MockWebServerRule server = new MockWebServerRule();

  @Test public void defaultMockResponse() {
    MockResponse response = new MockResponse();
    assertEquals(Arrays.asList("Content-Length: 0"), response.getHeaders());
    assertEquals("HTTP/1.1 200 OK", response.getStatus());
  }

  @Test public void setBodyAdjustsHeaders() throws IOException {
    MockResponse response = new MockResponse().setBody("ABC");
    assertEquals(Arrays.asList("Content-Length: 3"), response.getHeaders());
    BufferedSource source = Okio.buffer(response.getBody());
    assertEquals('A', source.readByte());
    assertEquals('B', source.readByte());
    assertEquals('C', source.readByte());
    assertTrue(source.exhausted());
    assertEquals("HTTP/1.1 200 OK", response.getStatus());
  }

  @Test public void mockResponseAddHeader() {
    MockResponse response = new MockResponse()
        .clearHeaders()
        .addHeader("Cookie: s=square")
        .addHeader("Cookie", "a=android");
    assertEquals(Arrays.asList("Cookie: s=square", "Cookie: a=android"), response.getHeaders());
  }

  @Test public void mockResponseSetHeader() {
    MockResponse response = new MockResponse()
        .clearHeaders()
        .addHeader("Cookie: s=square")
        .addHeader("Cookie: a=android")
        .addHeader("Cookies: delicious");
    response.setHeader("cookie", "r=robot");
    assertEquals(Arrays.asList("Cookies: delicious", "cookie: r=robot"), response.getHeaders());
  }

  /**
   * Clients who adhere to <a
   * href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html#sec8.2.3">100
   * Status</a> expect the server to send an interim response with status code
   * 100 before they send their payload.
   *
   * <h4>Note</h4>
   *
   * JRE 6 only passes this test if
   * {@code -Dsun.net.http.allowRestrictedHeaders=true} is set.
   */
  @Test public void expect100ContinueWithBody() throws Exception {
    server.enqueue(new MockResponse());

    URL url = server.getUrl("/");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("PUT");
    connection.setAllowUserInteraction(false);
    connection.setRequestProperty("Expect", "100-continue");
    connection.setDoOutput(true);
    connection.getOutputStream().write("hello".getBytes());
    assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());

    assertEquals(server.getRequestCount(), 1);
    RecordedRequest request = server.takeRequest();
    assertEquals(request.getRequestLine(), "PUT / HTTP/1.1");
    assertEquals("5", request.getHeader("Content-Length"));
    assertEquals(5, request.getBodySize());
    assertEquals("hello", request.getBody().readUtf8());
    // below fails on JRE 6 unless -Dsun.net.http.allowRestrictedHeaders=true is set
    assertEquals("100-continue", request.getHeader("Expect"));
  }

  @Test public void expect100ContinueWithNoBody() throws Exception {
    server.enqueue(new MockResponse());

    URL url = server.getUrl("/");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("PUT");
    connection.setAllowUserInteraction(false);
    connection.setRequestProperty("Expect", "100-continue");
    connection.setRequestProperty("Content-Length", "0");
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(0);
    assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());

    assertEquals(server.getRequestCount(), 1);
    RecordedRequest request = server.takeRequest();
    assertEquals(request.getRequestLine(), "PUT / HTTP/1.1");
    assertEquals("0", request.getHeader("Content-Length"));
    assertEquals(0, request.getBodySize());
    // below fails on JRE 6 unless -Dsun.net.http.allowRestrictedHeaders=true is set
    assertEquals("100-continue", request.getHeader("Expect"));
  }

  @Test public void regularResponse() throws Exception {
    server.enqueue(new MockResponse().setBody("hello world"));

    URL url = server.getUrl("/");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept-Language", "en-US");
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
    assertEquals("hello world", reader.readLine());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET / HTTP/1.1", request.getRequestLine());
    assertEquals("en-US", request.getHeaders().get("Accept-Language"));
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server.getUrl("/new-path"))
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("This is the new location!"));

    URLConnection connection = server.getUrl("/").openConnection();
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals("This is the new location!", reader.readLine());

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest redirect = server.takeRequest();
    assertEquals("GET /new-path HTTP/1.1", redirect.getRequestLine());
  }

  /**
   * Test that MockWebServer blocks for a call to enqueue() if a request
   * is made before a mock response is ready.
   */
  @Test public void dispatchBlocksWaitingForEnqueue() throws Exception {
    new Thread() {
      @Override public void run() {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        server.enqueue(new MockResponse().setBody("enqueued in the background"));
      }
    }.start();

    URLConnection connection = server.getUrl("/").openConnection();
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals("enqueued in the background", reader.readLine());
  }

  @Test public void nonHexadecimalChunkSize() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("G\r\nxxxxxxxxxxxxxxxx\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    URLConnection connection = server.getUrl("/").openConnection();
    InputStream in = connection.getInputStream();
    try {
      in.read();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void responseTimeout() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC")
        .clearHeaders()
        .addHeader("Content-Length: 4"));
    server.enqueue(new MockResponse().setBody("DEF"));

    URLConnection urlConnection = server.getUrl("/").openConnection();
    urlConnection.setReadTimeout(1000);
    InputStream in = urlConnection.getInputStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());
    try {
      in.read(); // if Content-Length was accurate, this would return -1 immediately
      fail();
    } catch (SocketTimeoutException expected) {
    }

    URLConnection urlConnection2 = server.getUrl("/").openConnection();
    InputStream in2 = urlConnection2.getInputStream();
    assertEquals('D', in2.read());
    assertEquals('E', in2.read());
    assertEquals('F', in2.read());
    assertEquals(-1, in2.read());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void disconnectAtStart() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(new MockResponse()); // The jdk's HttpUrlConnection is a bastard.
    server.enqueue(new MockResponse());
    try {
      server.getUrl("/a").openConnection().getInputStream();
    } catch (IOException expected) {
    }
    server.getUrl("/b").openConnection().getInputStream(); // Should succeed.
  }

  @Test public void streamingResponseBody() throws Exception {
    InputStream responseBody = new ByteArrayInputStream("ABC".getBytes("UTF-8"));
    server.enqueue(new MockResponse().setBody(responseBody, 3));

    InputStream in = server.getUrl("/").openConnection().getInputStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());

    assertEquals(-1, responseBody.read()); // The body is exhausted.
  }

  /**
   * Throttle the request body by sleeping 500ms after every 3 bytes. With a
   * 6-byte request, this should yield one sleep for a total delay of 500ms.
   */
  @Test public void throttleRequest() throws Exception {
    server.enqueue(new MockResponse()
        .throttleBody(3, 500, TimeUnit.MILLISECONDS));

    long startNanos = System.nanoTime();
    URLConnection connection = server.getUrl("/").openConnection();
    connection.setDoOutput(true);
    connection.getOutputStream().write("ABCDEF".getBytes("UTF-8"));
    InputStream in = connection.getInputStream();
    assertEquals(-1, in.read());
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 500);
    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis < 1000);
  }

  /**
   * Throttle the response body by sleeping 500ms after every 3 bytes. With a
   * 6-byte response, this should yield one sleep for a total delay of 500ms.
   */
  @Test public void throttleResponse() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABCDEF")
        .throttleBody(3, 500, TimeUnit.MILLISECONDS));

    long startNanos = System.nanoTime();
    URLConnection connection = server.getUrl("/").openConnection();
    InputStream in = connection.getInputStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());
    assertEquals('D', in.read());
    assertEquals('E', in.read());
    assertEquals('F', in.read());
    assertEquals(-1, in.read());
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 500);
    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis < 1000);
  }

  /**
   * Delay the response body by sleeping 1000ms.
   */
  @Test public void delayResponse() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("ABCDEF")
        .setBodyDelayTimeMs(1000));

    long startNanos = System.nanoTime();
    URLConnection connection = server.getUrl("/").openConnection();
    InputStream in = connection.getInputStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());
    assertEquals('D', in.read());
    assertEquals('E', in.read());
    assertEquals('F', in.read());
    assertEquals(-1, in.read());
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 1000);
    assertTrue(String.format("Request + Response: %sms", elapsedMillis), elapsedMillis <= 1100);
  }
}
