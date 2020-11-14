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

import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.internal.MockWebServerExtension;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Request;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockWebServerExtension.class)
public final class EventSourceHttpTest {
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

    newEventSource();
    listener.assertFailure("Invalid content-type: text/plain");
  }

  @Test public void badResponseCode() {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream").setResponseCode(401));

    newEventSource();
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

  @Test public void retainsAccept() throws InterruptedException {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream"));

    EventSource source = newEventSource("text/plain");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();

    assertThat(server.takeRequest().getHeader("Accept")).isEqualTo("text/plain");
  }

  @Test public void setsMissingAccept() throws InterruptedException {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream"));

    EventSource source = newEventSource();

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();

    assertThat(server.takeRequest().getHeader("Accept")).isEqualTo("text/event-stream");
  }

  private EventSource newEventSource() {
    return newEventSource(null);
  }

  private EventSource newEventSource(@Nullable String accept) {
    Request.Builder builder = new Request.Builder()
        .url(server.url("/"));

    if (accept != null) {
      builder.header("Accept", accept);
    }

    Request request = builder
        .build();
    EventSource.Factory factory = EventSources.createFactory(client);
    return factory.newEventSource(request, listener);
  }
}
