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
package com.squareup.okhttp.ws;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Version;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Exercises the web socket implementation against the
 * <a href="http://autobahn.ws/testsuite/">Autobahn Testsuite</a>.
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
      client.getDispatcher().getExecutorService().shutdown();
    }
  }

  private void runTest(final long number, final long count) throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    newWebSocket("/runCase?case=" + number + "&agent=" + Version.userAgent()) //
        .enqueue(new WebSocketListener() {
          private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
          private WebSocket webSocket;

          @Override public void onOpen(WebSocket webSocket, Response response) {
            System.out.println("Executing test case " + number + "/" + count);
            this.webSocket = webSocket;
          }

          @Override public void onMessage(BufferedSource payload, final WebSocket.PayloadType type)
              throws IOException {
            final Buffer buffer = new Buffer();
            payload.readAll(buffer);
            payload.close();

            sendExecutor.execute(new Runnable() {
              @Override public void run() {
                try {
                  webSocket.sendMessage(type, buffer);
                } catch (IOException e) {
                  e.printStackTrace();
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

  private long getTestCount() throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicLong countRef = new AtomicLong();
    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    newWebSocket("/getCaseCount").enqueue(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
      }

      @Override public void onMessage(BufferedSource payload, WebSocket.PayloadType type)
          throws IOException {
        countRef.set(payload.readDecimalLong());
        payload.close();
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

      @Override public void onMessage(BufferedSource payload, WebSocket.PayloadType type)
          throws IOException {
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
