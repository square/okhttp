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
package com.google.mockwebserver;

import java.net.HttpURLConnection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Default dispatcher that processes a script of responses.  Populate the script by calling
 * {@link #enqueueResponse(MockResponse)}.
 */
final class QueueDispatcher extends Dispatcher {
    private final BlockingQueue<MockResponse> responseQueue
            = new LinkedBlockingQueue<MockResponse>();

    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        // to permit interactive/browser testing, ignore requests for favicons
        final String requestLine = request.getRequestLine();
        if (requestLine != null && requestLine.equals("GET /favicon.ico HTTP/1.1")) {
            System.out.println("served " + requestLine);
            return new MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
        }

        return responseQueue.take();
    }

    @Override
    public SocketPolicy peekSocketPolicy() {
        final MockResponse peek = responseQueue.peek();
        if (peek == null) return SocketPolicy.KEEP_OPEN;
        return peek.getSocketPolicy();
    }

    public void enqueueResponse(MockResponse response) {
        responseQueue.add(response);
    }
}
