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
package okhttp3;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.internal.Version;
import okio.ByteString;

/**
 * Exercises the web socket implementation against the <a
 * href="http://autobahn.ws/testsuite/">Autobahn Testsuite</a>.
 */
public final class AutobahnTester {
  private static final String HOST = "ws://localhost:9099";

  public static void main(String... args) throws IOException {
    new AutobahnTester().run();
  }

  final OkHttpClient client = new OkHttpClient();

  private WebSocket newWebSocket(String path, WebSocketListener listener) {
    Request request = new Request.Builder().url(HOST + path).build();
    return client.newWebSocket(request, listener);
  }

  public void run() throws IOException {
    try {
      long count = getTestCount();
      System.out.println("Test count: " + count);

      for (long number = 1; number <= count; number++) {
        runTest(number, count);
      }

      updateReports();
    } finally {
      client.dispatcher().executorService().shutdown();
    }
  }

  private void runTest(final long number, final long count) {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicLong startNanos = new AtomicLong();
    newWebSocket("/runCase?case=" + number + "&agent=okhttp", new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
        System.out.println("Executing test case " + number + "/" + count);
        startNanos.set(System.nanoTime());
      }

      @Override public void onMessage(final WebSocket webSocket, final ByteString bytes) {
        webSocket.send(bytes);
      }

      @Override public void onMessage(final WebSocket webSocket, final String text) {
        webSocket.send(text);
      }

      @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        latch.countDown();
      }

      @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        t.printStackTrace(System.out);
        latch.countDown();
      }
    });
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test " + number + " to finish.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError();
    }

    long endNanos = System.nanoTime();
    long tookMs = TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos.get());
    System.out.println("Took " + tookMs + "ms");
  }

  private long getTestCount() throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicLong countRef = new AtomicLong();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    newWebSocket("/getCaseCount", new WebSocketListener() {
      @Override public void onMessage(WebSocket webSocket, String text) {
        countRef.set(Long.parseLong(text));
      }

      @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        latch.countDown();
      }

      @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        failureRef.set(t);
        latch.countDown();
      }
    });
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for count.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError();
    }
    Throwable failure = failureRef.get();
    if (failure != null) {
      throw new RuntimeException(failure);
    }
    return countRef.get();
  }

  private void updateReports() {
    final CountDownLatch latch = new CountDownLatch(1);
    newWebSocket("/updateReports?agent=" + Version.userAgent, new WebSocketListener() {
      @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        latch.countDown();
      }

      @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        latch.countDown();
      }
    });
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for count.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError();
    }
  }
}
