/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okio.Buffer;

/**
 * Records received HTTP responses so they can be later retrieved by tests.
 */
public class RecordingCallback implements Callback {
  public static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private final List<RecordedResponse> responses = new ArrayList<>();

  @Override public synchronized void onFailure(Request request, IOException e) {
    responses.add(new RecordedResponse(request, null, null, e));
    notifyAll();
  }

  @Override public synchronized void onResponse(Response response) throws IOException {
    Buffer buffer = new Buffer();
    ResponseBody body = response.body();
    body.source().readAll(buffer);

    responses.add(new RecordedResponse(response.request(), response, buffer.readUtf8(), null));
    notifyAll();
  }

  /**
   * Returns the recorded response triggered by {@code request}. Throws if the
   * response isn't enqueued before the timeout.
   */
  public synchronized RecordedResponse await(URL url) throws Exception {
    long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + TIMEOUT_MILLIS;
    while (true) {
      for (Iterator<RecordedResponse> i = responses.iterator(); i.hasNext(); ) {
        RecordedResponse recordedResponse = i.next();
        if (recordedResponse.request.url().equals(url)) {
          i.remove();
          return recordedResponse;
        }
      }

      long nowMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
      if (nowMillis >= timeoutMillis) break;
      wait(timeoutMillis - nowMillis);
    }

    throw new AssertionError("Timed out waiting for response to " + url);
  }

  public synchronized void assertNoResponse(URL url) throws Exception {
    for (RecordedResponse recordedResponse : responses) {
      if (recordedResponse.request.url().equals(url)) {
        throw new AssertionError("Expected no response for " + url);
      }
    }
  }
}
