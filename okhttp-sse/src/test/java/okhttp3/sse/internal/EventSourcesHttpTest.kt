/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.sse.internal;

import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.internal.MockWebServerExtension;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSources;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@Tag("Slowish")
@ExtendWith(MockWebServerExtension.class)
public final class EventSourcesHttpTest {
  @RegisterExtension public final PlatformRule platform = new PlatformRule();

  private MockWebServer server;
  @RegisterExtension public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private final EventSourceRecorder listener = new EventSourceRecorder();
  private OkHttpClient client = clientTestRule.newClient();

  @BeforeEach public void before(MockWebServer server) {
    this.server = server;
  }

  @AfterEach public void after() {
    listener.assertExhausted();
  }

  @Test public void processResponse() throws IOException {
    server.enqueue(new MockResponse.Builder()
      .body(""
          + "data: hey\n"
          + "\n").setHeader("content-type", "text/event-stream")
      .build());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();
    EventSources.processResponse(response, listener);

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();
  }

  @Test public void cancelShortCircuits() throws IOException {
    server.enqueue(new MockResponse.Builder()
      .body(""
          + "data: hey\n"
          + "\n").setHeader("content-type", "text/event-stream")
      .build());
    listener.enqueueCancel(); // Will cancel in onOpen().

    Request request = new Request.Builder()
      .url(server.url("/"))
      .build();
    Response response = client.newCall(request).execute();
    EventSources.processResponse(response, listener);

    listener.assertOpen();
    listener.assertFailure("canceled");
  }
}
