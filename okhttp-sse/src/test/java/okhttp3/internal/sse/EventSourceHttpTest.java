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
package okhttp3.internal.sse;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public final class EventSourceHttpTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private final EventSourceRecorder listener = new EventSourceRecorder();
  private OkHttpClient client = clientTestRule.newClient();

  @After public void after() {
    listener.assertExhausted();
  }

  @Test public void event() {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream"));

    EventSource source = newEventSource();

    assertThat(source.request().url().encodedPath()).isEqualTo("/");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();
  }

  @Test public void badContentType() {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/plain"));

    EventSource source = newEventSource();
    listener.assertFailure("Invalid content-type: text/plain");
  }

  @Test public void badResponseCode() {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream").setResponseCode(401));

    EventSource source = newEventSource();
    listener.assertFailure(null);
  }

  @Test public void fullCallTimeoutDoesNotApplyOnceConnected() throws Exception {
    client = client.newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    server.enqueue(new MockResponse()
        .setBodyDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .setBody("data: hey\n\n"));

    EventSource source = newEventSource();

    assertThat(source.request().url().encodedPath()).isEqualTo("/");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();
  }

  @Test public void fullCallTimeoutAppliesToSetup() throws Exception {
    client = client.newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    server.enqueue(new MockResponse()
        .setHeadersDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .setBody("data: hey\n\n"));

    newEventSource();
    listener.assertFailure("timeout");
  }

  private EventSource newEventSource() {
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    EventSource.Factory factory = EventSources.createFactory(client);
    return factory.newEventSource(request, listener);
  }
}
