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
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.internal.MockWebServerExtension;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.RecordingEventListener;
import okhttp3.Request;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("Slowish")
@ExtendWith(MockWebServerExtension.class)
public final class EventSourceHttpTest {
  @RegisterExtension public final PlatformRule platform = new PlatformRule();

  private MockWebServer server;
  @RegisterExtension public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private final RecordingEventListener eventListener = new RecordingEventListener();

  private final EventSourceRecorder listener = new EventSourceRecorder();
  private OkHttpClient client = clientTestRule.newClientBuilder()
    .eventListenerFactory(clientTestRule.wrap(eventListener))
    .build();

  @BeforeEach public void before(MockWebServer server) {
    this.server = server;
  }

  @AfterEach public void after() {
    listener.assertExhausted();
  }

  @Test public void event() {
    server.enqueue(new MockResponse.Builder()
        .body(""
            + "data: hey\n"
            + "\n").setHeader("content-type", "text/event-stream")
        .build());

    EventSource source = newEventSource();

    assertThat(source.request().url().encodedPath()).isEqualTo("/");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();
  }

  @RetryingTest(5)
  public void cancelInEventShortCircuits() throws IOException {
    server.enqueue(new MockResponse.Builder()
        .body(""
            + "data: hey\n"
            + "\n").setHeader("content-type", "text/event-stream")
        .build());
    listener.enqueueCancel(); // Will cancel in onOpen().

    newEventSource();
    listener.assertOpen();
    listener.assertFailure("canceled");
  }

  @Test public void badContentType() {
    server.enqueue(new MockResponse.Builder()
        .body(""
            + "data: hey\n"
            + "\n").setHeader("content-type", "text/plain")
        .build());

    newEventSource();
    listener.assertFailure("Invalid content-type: text/plain");
  }

  @Test public void badResponseCode() {
    server.enqueue(new MockResponse.Builder()
        .body(""
          + "data: hey\n"
          + "\n")
        .setHeader("content-type", "text/event-stream")
        .code(401)
        .build());

    newEventSource();
    listener.assertFailure(null);
  }

  @Test public void fullCallTimeoutDoesNotApplyOnceConnected() throws Exception {
    client = client.newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    server.enqueue(new MockResponse.Builder()
        .bodyDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build());

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

    server.enqueue(new MockResponse.Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build());

    newEventSource();
    listener.assertFailure("timeout");
  }

  @Test public void retainsAccept() throws InterruptedException {
    server.enqueue(new MockResponse.Builder()
        .body(""
            + "data: hey\n"
            + "\n").setHeader("content-type", "text/event-stream")
        .build());

    EventSource source = newEventSource("text/plain");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();

    assertThat(server.takeRequest().getHeaders().get("Accept")).isEqualTo("text/plain");
  }

  @Test public void setsMissingAccept() throws InterruptedException {
    server.enqueue(new MockResponse.Builder()
        .body(""
            + "data: hey\n"
            + "\n").setHeader("content-type", "text/event-stream")
        .build());

    EventSource source = newEventSource();

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();

    assertThat(server.takeRequest().getHeaders().get("Accept")).isEqualTo("text/event-stream");
  }

  @Test public void eventListenerEvents() {
    server.enqueue(new MockResponse.Builder()
        .body(""
          + "data: hey\n"
          + "\n").setHeader("content-type", "text/event-stream")
        .build());

    EventSource source = newEventSource();

    assertThat(source.request().url().encodedPath()).isEqualTo("/");

    listener.assertOpen();
    listener.assertEvent(null, null, "hey");
    listener.assertClose();

    assertThat(eventListener.recordedEventTypes()).containsExactly(
        "CallStart",
        "ProxySelectStart",
        "ProxySelectEnd",
        "DnsStart",
        "DnsEnd",
        "ConnectStart",
        "ConnectEnd",
        "ConnectionAcquired",
        "RequestHeadersStart",
        "RequestHeadersEnd",
        "ResponseHeadersStart",
        "ResponseHeadersEnd",
        "ResponseBodyStart",
        "ResponseBodyEnd",
        "ConnectionReleased",
        "CallEnd"
    );
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
