/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.mockwebserver.internal.duplex;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.RecordedRequest;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Utf8;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A scriptable request/response conversation. Create the script by calling methods like {@link
 * #receiveRequest} in the sequence they are run.
 */
public final class MockDuplexResponseBody implements DuplexResponseBody {
  private final BlockingQueue<Action> actions = new LinkedBlockingQueue<>();
  private final BlockingQueue<FutureTask<Void>> results = new LinkedBlockingQueue<>();

  public MockDuplexResponseBody receiveRequest(String expected) {
    actions.add((request, requestBody, responseBody) -> {
      assertEquals(expected, requestBody.readUtf8(Utf8.size(expected)));
    });
    return this;
  }

  public MockDuplexResponseBody exhaustRequest() {
    actions.add((request, requestBody, responseBody) -> {
      assertTrue(requestBody.exhausted());
    });
    return this;
  }

  public MockDuplexResponseBody sendResponse(String s) {
    actions.add((request, requestBody, responseBody) -> {
      responseBody.writeUtf8(s);
      responseBody.flush();
    });
    return this;
  }

  public MockDuplexResponseBody exhaustResponse() {
    actions.add((request, requestBody, responseBody) -> {
      responseBody.close();
    });
    return this;
  }

  @Override public void onRequest(RecordedRequest request, BufferedSource requestBody,
      BufferedSink responseBody) {
    FutureTask<Void> futureTask = new FutureTask<>(() -> {
      for (Action action; (action = actions.poll()) != null; ) {
        action.execute(request, requestBody, responseBody);
      }
      return null; // Success!
    });
    results.add(futureTask);
    futureTask.run();
  }

  /** Returns once the duplex conversation completes successfully. */
  public void awaitSuccess() throws Exception {
    FutureTask<Void> futureTask = results.poll(5, TimeUnit.SECONDS);
    if (futureTask == null) throw new AssertionError("no onRequest call received");
    futureTask.get(5, TimeUnit.SECONDS);
  }

  private interface Action {
    void execute(RecordedRequest request, BufferedSource requestBody, BufferedSink responseBody)
        throws IOException;
  }
}

