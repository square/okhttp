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
package okhttp3.ws;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Version;
import okio.Buffer;
import okio.BufferedSource;

import static okhttp3.ws.WebSocket.BINARY;
import static okhttp3.ws.WebSocket.TEXT;

/**
 * Exercises the web socket implementation against the <a
 * href="http://autobahn.ws/testsuite/">Autobahn Testsuite</a>.
 */
public final class AutobahnTester {
  private static final String HOST = "ws://localhost:9001";

  public static void main(String... args) throws IOException {
    new AutobahnTester().run();
  }

  final OkHttpClient client = new OkHttpClient();

  private WebSocketCall newWebSocket(String path) {
    Request request = new Request.Builder().url(HOST + path).build();
    return WebSocketCall.create(client, request);
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
      client.dispatcher().getExecutorService().shutdown();
    }
  }

  private void runTest(final long number, final long count) throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicLong startNanos = new AtomicLong();
    newWebSocket("/runCase?case=" + number + "&agent=okhttp") //
        .enqueue(new WebSocketListener() {
          private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
          private WebSocket webSocket;

          @Override public void onOpen(WebSocket webSocket, Response response) {
            this.webSocket = webSocket;

            System.out.println("Executing test case " + number + "/" + count);
            startNanos.set(System.nanoTime());
          }

          @Override public void onMessage(final ResponseBody message) throws IOException {
            final RequestBody response;
            if (message.contentType() == TEXT) {
              response = RequestBody.create(TEXT, message.string());
            } else {
              BufferedSource source = message.source();
              response = RequestBody.create(BINARY, source.readByteString());
              source.close();
            }
            sendExecutor.execute(new Runnable() {
              @Override public void run() {
                try {
                  webSocket.sendMessage(response);
                } catch (IOException e) {
                  e.printStackTrace(System.out);
                }
              }
            });
          }

          @Override public void onPong(Buffer payload) {
          }

          @Override public void onClose(int code, String reason) {
            sendExecutor.shutdown();
            latch.countDown();
          }

          @Override public void onFailure(IOException e, Response response) {
            e.printStackTrace(System.out);
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
    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    newWebSocket("/getCaseCount").enqueue(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
      }

      @Override public void onMessage(ResponseBody message) throws IOException {
        countRef.set(message.source().readDecimalLong());
        message.close();
      }

      @Override public void onPong(Buffer payload) {
      }

      @Override public void onClose(int code, String reason) {
        latch.countDown();
      }

      @Override public void onFailure(IOException e, Response response) {
        failureRef.set(e);
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
    IOException failure = failureRef.get();
    if (failure != null) {
      throw failure;
    }
    return countRef.get();
  }

  private void updateReports() {
    final CountDownLatch latch = new CountDownLatch(1);
    newWebSocket("/updateReports?agent=" + Version.userAgent()).enqueue(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
      }

      @Override public void onMessage(ResponseBody message) throws IOException {
      }

      @Override public void onPong(Buffer payload) {
      }

      @Override public void onClose(int code, String reason) {
        latch.countDown();
      }

      @Override public void onFailure(IOException e, Response response) {
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
