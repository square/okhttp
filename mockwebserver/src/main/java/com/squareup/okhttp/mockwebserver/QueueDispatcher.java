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
package com.squareup.okhttp.mockwebserver;

import java.net.HttpURLConnection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Default dispatcher that processes a script of responses. Populate the script
 * by calling {@link #enqueueResponse(MockResponse)}.
 */
public class QueueDispatcher extends Dispatcher {
  private static final Logger logger = Logger.getLogger(QueueDispatcher.class.getName());
  protected final BlockingQueue<MockResponse> responseQueue = new LinkedBlockingQueue<>();
  private MockResponse failFastResponse;

  @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
    // To permit interactive/browser testing, ignore requests for favicons.
    final String requestLine = request.getRequestLine();
    if (requestLine != null && requestLine.equals("GET /favicon.ico HTTP/1.1")) {
      logger.info("served " + requestLine);
      return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
    }

    if (failFastResponse != null && responseQueue.peek() == null) {
      // Fail fast if there's no response queued up.
      return failFastResponse;
    }

    return responseQueue.take();
  }

  @Override public MockResponse peek() {
    MockResponse peek = responseQueue.peek();
    if (peek != null) return peek;
    if (failFastResponse != null) return failFastResponse;
    return super.peek();
  }

  public void enqueueResponse(MockResponse response) {
    responseQueue.add(response);
  }

  public void setFailFast(boolean failFast) {
    MockResponse failFastResponse = failFast
        ? new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
        : null;
    setFailFast(failFastResponse);
  }

  public void setFailFast(MockResponse failFastResponse) {
    this.failFastResponse = failFastResponse;
  }
}
