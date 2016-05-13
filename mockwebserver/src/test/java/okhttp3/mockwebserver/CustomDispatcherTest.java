/*
 * Copyright (C) 2012 Google Inc.
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CustomDispatcherTest {
  private MockWebServer mockWebServer = new MockWebServer();

  @After public void tearDown() throws Exception {
    mockWebServer.shutdown();
  }

  @Test public void simpleDispatch() throws Exception {
    mockWebServer.start();
    final List<RecordedRequest> requestsMade = new ArrayList<>();
    final Dispatcher dispatcher = new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        requestsMade.add(request);
        return new MockResponse();
      }
    };
    assertEquals(0, requestsMade.size());
    mockWebServer.setDispatcher(dispatcher);
    final URL url = mockWebServer.url("/").url();
    final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.getResponseCode(); // Force the connection to hit the "server".
    // Make sure our dispatcher got the request.
    assertEquals(1, requestsMade.size());
  }

  @Test public void outOfOrderResponses() throws Exception {
    AtomicInteger firstResponseCode = new AtomicInteger();
    AtomicInteger secondResponseCode = new AtomicInteger();
    mockWebServer.start();
    final String secondRequest = "/bar";
    final String firstRequest = "/foo";
    final CountDownLatch latch = new CountDownLatch(1);
    final Dispatcher dispatcher = new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        if (request.getPath().equals(firstRequest)) {
          latch.await();
        }
        return new MockResponse();
      }
    };
    mockWebServer.setDispatcher(dispatcher);
    final Thread startsFirst = buildRequestThread(firstRequest, firstResponseCode);
    startsFirst.start();
    final Thread endsFirst = buildRequestThread(secondRequest, secondResponseCode);
    endsFirst.start();
    endsFirst.join();
    assertEquals(0, firstResponseCode.get()); // First response is still waiting.
    assertEquals(200, secondResponseCode.get()); // Second response is done.
    latch.countDown();
    startsFirst.join();
    assertEquals(200, firstResponseCode.get()); // And now it's done!
    assertEquals(200, secondResponseCode.get()); // (Still done).
  }

  private Thread buildRequestThread(final String path, final AtomicInteger responseCode) {
    return new Thread(new Runnable() {
      @Override public void run() {
        final URL url = mockWebServer.url(path).url();
        final HttpURLConnection conn;
        try {
          conn = (HttpURLConnection) url.openConnection();
          responseCode.set(conn.getResponseCode()); // Force the connection to hit the "server".
        } catch (IOException e) {
        }
      }
    });
  }
}
