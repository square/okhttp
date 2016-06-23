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
package okhttp3.mockwebserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Headers;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MockWebServerTest {
  @Rule public final MockWebServer server = new MockWebServer();

  @Test public void defaultMockResponse() {
    MockResponse response = new MockResponse();
    assertEquals(Arrays.asList("Content-Length: 0"), headersToList(response));
    assertEquals("HTTP/1.1 200 OK", response.getStatus());
  }

  @Test public void setResponseMockReason() {
    String[] reasons = {
        "Mock Response",
        "Informational",
        "OK",
        "Redirection",
        "Client Error",
        "Server Error",
        "Mock Response"
    };
    for (int i = 0; i < 600; i++) {
      MockResponse response = new MockResponse().setResponseCode(i);
      String expectedReason = reasons[i / 100];
      assertEquals("HTTP/1.1 " + i + " " + expectedReason, response.getStatus());
      assertEquals(Arrays.asList("Content-Length: 0"), headersToList(response));
    }
  }

  @Test public void setStatusControlsWholeStatusLine() {
    MockResponse response = new MockResponse().setStatus("HTTP/1.1 202 That'll do pig");
    assertEquals(Arrays.asList("Content-Length: 0"), headersToList(response));
    assertEquals("HTTP/1.1 202 That'll do pig", response.getStatus());
  }

  @Test public void setBodyAdjustsHeaders() throws IOException {
    MockResponse response = new MockResponse().setBody("ABC");
    assertEquals(Arrays.asList("Content-Length: 3"), headersToList(response));
    assertEquals("ABC", response.getBody().readUtf8());
  }

  @Test public void mockResponseAddHeader() {
    MockResponse response = new MockResponse()
        .clearHeaders()
        .addHeader("Cookie: s=square")
        .addHeader("Cookie", "a=android");
    assertEquals(Arrays.asList("Cookie: s=square", "Cookie: a=android"), headersToList(response));
  }

  @Test public void mockResponseSetHeader() {
    MockResponse response = new MockResponse()
        .clearHeaders()
        .addHeader("Cookie: s=square")
        .addHeader("Cookie: a=android")
        .addHeader("Cookies: delicious");
    response.setHeader("cookie", "r=robot");
    assertEquals(Arrays.asList("Cookies: delicious", "cookie: r=robot"), headersToList(response));
  }

  @Test public void regularResponse() throws Exception {
    server.enqueue(new MockResponse().setBody("hello world"));

    URL url = server.url("/").url();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept-Language", "en-US");
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
    assertEquals("hello world", reader.readLine());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET / HTTP/1.1", request.getRequestLine());
    assertEquals("en-US", request.getHeader("Accept-Language"));
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server.url("/new-path"))
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("This is the new location!"));

    URLConnection connection = server.url("/").url().openConnection();
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals("This is the new location!", reader.readLine());

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest redirect = server.takeRequest();
    assertEquals("GET /new-path HTTP/1.1", redirect.getRequestLine());
  }

  /**
   * Test that MockWebServer blocks for a call to enqueue() if a request is made before a mock
   * response is ready.
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

    URLConnection connection = server.url("/").url().openConnection();
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals("enqueued in the background", reader.readLine());
  }

  @Test public void nonHexadecimalChunkSize() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("G\r\nxxxxxxxxxxxxxxxx\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    URLConnection connection = server.url("/").url().openConnection();
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

    URLConnection urlConnection = server.url("/").url().openConnection();
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

    URLConnection urlConnection2 = server.url("/").url().openConnection();
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
      server.url("/a").url().openConnection().getInputStream();
    } catch (IOException expected) {
    }
    server.url("/b").url().openConnection().getInputStream(); // Should succeed.
  }

  /**
   * Throttle the request body by sleeping 500ms after every 3 bytes. With a 6-byte request, this
   * should yield one sleep for a total delay of 500ms.
   */
  @Test public void throttleRequest() throws Exception {
    server.enqueue(new MockResponse()
        .throttleBody(3, 500, TimeUnit.MILLISECONDS));

    long startNanos = System.nanoTime();
    URLConnection connection = server.url("/").url().openConnection();
    connection.setDoOutput(true);
    connection.getOutputStream().write("ABCDEF".getBytes("UTF-8"));
    InputStream in = connection.getInputStream();
    assertEquals(-1, in.read());
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    assertTrue(Util.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 500);
    assertTrue(Util.format("Request + Response: %sms", elapsedMillis), elapsedMillis < 1000);
  }

  /**
   * Throttle the response body by sleeping 500ms after every 3 bytes. With a 6-byte response, this
   * should yield one sleep for a total delay of 500ms.
   */
  @Test public void throttleResponse() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABCDEF")
        .throttleBody(3, 500, TimeUnit.MILLISECONDS));

    long startNanos = System.nanoTime();
    URLConnection connection = server.url("/").url().openConnection();
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

    assertTrue(Util.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 500);
    assertTrue(Util.format("Request + Response: %sms", elapsedMillis), elapsedMillis < 1000);
  }

  /** Delay the response body by sleeping 1s. */
  @Test public void delayResponse() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("ABCDEF")
        .setBodyDelay(1, SECONDS));

    long startNanos = System.nanoTime();
    URLConnection connection = server.url("/").url().openConnection();
    InputStream in = connection.getInputStream();
    assertEquals('A', in.read());
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);
    assertTrue(Util.format("Request + Response: %sms", elapsedMillis), elapsedMillis >= 1000);

    in.close();
  }

  @Test public void disconnectRequestHalfway() throws IOException {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));
    // Limit the size of the request body that the server holds in memory to an arbitrary
    // 3.5 MBytes so this test can pass on devices with little memory.
    server.setBodyLimit(7 * 512 * 1024);

    HttpURLConnection connection = (HttpURLConnection) server.url("/").url().openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(1024 * 1024 * 1024); // 1 GB
    connection.connect();
    OutputStream out = connection.getOutputStream();

    byte[] data = new byte[1024 * 1024];
    int i;
    for (i = 0; i < 1024; i++) {
      try {
        out.write(data);
        out.flush();
      } catch (IOException e) {
        break;
      }
    }
    assertEquals(512f, i, 10f); // Halfway +/- 1%
  }

  @Test public void disconnectResponseHalfway() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("ab")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    URLConnection connection = server.url("/").url().openConnection();
    assertEquals(2, connection.getContentLength());
    InputStream in = connection.getInputStream();
    assertEquals('a', in.read());
    try {
      int byteRead = in.read();
      // OpenJDK behavior: end of stream.
      assertEquals(-1, byteRead);
    } catch (ProtocolException e) {
      // On Android, HttpURLConnection is implemented by OkHttp v2. OkHttp
      // treats an incomplete response body as a ProtocolException.
    }
  }

  private List<String> headersToList(MockResponse response) {
    Headers headers = response.getHeaders();
    int size = headers.size();
    List<String> headerList = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      headerList.add(headers.name(i) + ": " + headers.value(i));
    }
    return headerList;
  }

  @Test public void shutdownWithoutStart() throws IOException {
    MockWebServer server = new MockWebServer();
    server.shutdown();
  }

  @Test public void shutdownWithoutEnqueue() throws IOException {
    MockWebServer server = new MockWebServer();
    server.start();
    server.shutdown();
  }

  @After public void tearDown() throws IOException {
    server.shutdown();
  }

  @Test public void portImplicitlyStarts() throws IOException {
    assertTrue(server.getPort() > 0);
  }

  @Test public void hostnameImplicitlyStarts() throws IOException {
    assertNotNull(server.getHostName());
  }

  @Test public void toProxyAddressImplicitlyStarts() throws IOException {
    assertNotNull(server.toProxyAddress());
  }

  @Test public void differentInstancesGetDifferentPorts() throws IOException {
    MockWebServer other = new MockWebServer();
    assertNotEquals(server.getPort(), other.getPort());
    other.shutdown();
  }

  @Test public void statementStartsAndStops() throws Throwable {
    final AtomicBoolean called = new AtomicBoolean();
    Statement statement = server.apply(new Statement() {
      @Override public void evaluate() throws Throwable {
        called.set(true);
        server.url("/").url().openConnection().connect();
      }
    }, Description.EMPTY);

    statement.evaluate();

    assertTrue(called.get());
    try {
      server.url("/").url().openConnection().connect();
      fail();
    } catch (ConnectException expected) {
    }
  }

  @Test public void shutdownWhileBlockedDispatching() throws Exception {
    // Enqueue a request that'll cause MockWebServer to hang on QueueDispatcher.dispatch().
    HttpURLConnection connection = (HttpURLConnection) server.url("/").url().openConnection();
    connection.setReadTimeout(500);
    try {
      connection.getResponseCode();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    // Shutting down the server should unblock the dispatcher.
    server.shutdown();
  }
}
