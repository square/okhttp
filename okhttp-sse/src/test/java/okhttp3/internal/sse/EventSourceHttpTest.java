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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EventSourceHttpTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final EventSourceRecorder listener = new EventSourceRecorder();
  private final EventSource.Factory factory = EventSources.createFactory(new OkHttpClient());

  @After public void after() {
    listener.assertExhausted();
  }

  @Test public void event() {
    server.enqueue(new MockResponse().setBody(""
        + "data: hey\n"
        + "\n").setHeader("content-type", "text/event-stream"));

    EventSource source = newEventSource();

    assertEquals("/", source.request().url().encodedPath());

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

  private EventSource newEventSource() {
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    return factory.newEventSource(request, listener);
  }
}
