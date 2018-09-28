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

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import javax.annotation.Nullable;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class EventSourceRecorder extends EventSourceListener {
  private final BlockingQueue<Object> events = new LinkedBlockingDeque<>();

  @Override public void onOpen(EventSource eventSource, Response response) {
    Platform.get().log(Platform.INFO, "[ES] onOpen", null);
    events.add(new Open(eventSource, response));
  }

  @Override public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type,
      String data) {
    Platform.get().log(Platform.INFO, "[ES] onEvent", null);
    events.add(new Event(id, type, data));
  }

  @Override public void onClosed(EventSource eventSource) {
    Platform.get().log(Platform.INFO, "[ES] onClosed", null);
    events.add(new Closed());
  }

  @Override
  public void onFailure(EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
    Platform.get().log(Platform.INFO, "[ES] onFailure", t);
    events.add(new Failure(t, response));
  }

  private Object nextEvent() {
    try {
      Object event = events.poll(10, SECONDS);
      if (event == null) {
        throw new AssertionError("Timed out waiting for event.");
      }
      return event;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void assertExhausted() {
    assertTrue("Remaining events: " + events, events.isEmpty());
  }

  public void assertEvent(@Nullable String id, @Nullable String type, String data) {
    Object actual = nextEvent();
    assertEquals(new Event(id, type, data), actual);
  }

  public EventSource assertOpen() {
    Object event = nextEvent();
    if (!(event instanceof Open)) {
      throw new AssertionError("Expected Open but was " + event);
    }
    return ((Open) event).eventSource;
  }

  public void assertClose() {
    Object event = nextEvent();
    if (!(event instanceof Closed)) {
      throw new AssertionError("Expected Open but was " + event);
    }
  }

  public void assertFailure(@Nullable String message) {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    if (message != null) {
      assertEquals(message, ((Failure) event).t.getMessage());
    } else {
      assertNull(((Failure) event).t);
    }
  }

  static final class Open {
    final EventSource eventSource;
    final Response response;

    Open(EventSource eventSource, Response response) {
      this.eventSource = eventSource;
      this.response = response;
    }

    @Override public String toString() {
      return "Open[" + response + ']';
    }
  }

  static final class Failure {
    final Throwable t;
    final Response response;
    final String responseBody;

    Failure(Throwable t, Response response) {
      this.t = t;
      this.response = response;
      String responseBody = null;
      if (response != null) {
        try {
          responseBody = response.body().string();
        } catch (IOException ignored) {
        }
      }
      this.responseBody = responseBody;
    }

    @Override public String toString() {
      if (response == null) {
        return "Failure[" + t + "]";
      }
      return "Failure[" + response + "]";
    }
  }

  static final class Closed {
    @Override public String toString() {
      return "Closed[]";
    }
  }
}
