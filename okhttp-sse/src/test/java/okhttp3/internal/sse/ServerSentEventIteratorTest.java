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
import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;
import okio.Buffer;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ServerSentEventIteratorTest {
  /** Either {@link Event} or {@link Long} items for events and retry changes, respectively. */
  private final Deque<Object> callbacks = new ArrayDeque<>();

  @After public void after() {
    assertTrue("Unconsumed events: " + callbacks, callbacks.isEmpty());
  }
  
  @Test public void multiline() throws IOException {
    consumeEvents(""
        + "data: YHOO\n"
        + "data: +2\n"
        + "data: 10\n"
        + "\n");
    assertEquals(new Event(null, null, "YHOO\n+2\n10"), callbacks.remove());
  }

  @Test public void multilineCr() throws IOException {
    consumeEvents(""
        + "data: YHOO\r"
        + "data: +2\r"
        + "data: 10\r"
        + "\r");
    assertEquals(new Event(null, null, "YHOO\n+2\n10"), callbacks.remove());
  }

  @Test public void multilineCrLf() throws IOException {
    consumeEvents(""
        + "data: YHOO\r\n"
        + "data: +2\r\n"
        + "data: 10\r\n"
        + "\r\n");
    assertEquals(new Event(null, null, "YHOO\n+2\n10"), callbacks.remove());
  }

  @Test public void eventType() throws IOException {
    consumeEvents(""
        + "event: add\n"
        + "data: 73857293\n"
        + "\n"
        + "event: remove\n"
        + "data: 2153\n"
        + "\n"
        + "event: add\n"
        + "data: 113411\n"
        + "\n");
    assertEquals(new Event(null, "add", "73857293"), callbacks.remove());
    assertEquals(new Event(null, "remove", "2153"), callbacks.remove());
    assertEquals(new Event(null, "add", "113411"), callbacks.remove());
  }

  @Test public void commentsIgnored() throws IOException {
    consumeEvents(""
        + ": test stream\n"
        + "\n"
        + "data: first event\n"
        + "id: 1\n"
        + "\n");
    assertEquals(new Event("1", null, "first event"), callbacks.remove());
  }

  @Test public void idCleared() throws IOException {
    consumeEvents(""
        + "data: first event\n"
        + "id: 1\n"
        + "\n"
        + "data: second event\n"
        + "id\n"
        + "\n"
        + "data: third event\n"
        + "\n");
    assertEquals(new Event("1", null, "first event"), callbacks.remove());
    assertEquals(new Event(null, null, "second event"), callbacks.remove());
    assertEquals(new Event(null, null, "third event"), callbacks.remove());
  }

  @Test public void nakedFieldNames() throws IOException {
    consumeEvents(""
        + "data\n"
        + "\n"
        + "data\n"
        + "data\n"
        + "\n"
        + "data:\n");
    assertEquals(new Event(null, null, ""), callbacks.remove());
    assertEquals(new Event(null, null, "\n"), callbacks.remove());
  }

  @Test public void colonSpaceOptional() throws IOException {
    consumeEvents(""
        + "data:test\n"
        + "\n"
        + "data: test\n"
        + "\n");
    assertEquals(new Event(null, null, "test"), callbacks.remove());
    assertEquals(new Event(null, null, "test"), callbacks.remove());
  }

  @Test public void leadingWhitespace() throws IOException {
    consumeEvents(""
        + "data:  test\n"
        + "\n");
    assertEquals(new Event(null, null, " test"), callbacks.remove());
  }

  @Test public void idReusedAcrossEvents() throws IOException {
    consumeEvents(""
        + "data: first event\n"
        + "id: 1\n"
        + "\n"
        + "data: second event\n"
        + "\n"
        + "id: 2\n"
        + "data: third event\n"
        + "\n");
    assertEquals(new Event("1", null, "first event"), callbacks.remove());
    assertEquals(new Event("1", null, "second event"), callbacks.remove());
    assertEquals(new Event("2", null, "third event"), callbacks.remove());
  }

  @Test public void idIgnoredFromEmptyEvent() throws IOException {
    consumeEvents(""
        + "data: first event\n"
        + "id: 1\n"
        + "\n"
        + "id: 2\n"
        + "\n"
        + "data: second event\n"
        + "\n");
    assertEquals(new Event("1", null, "first event"), callbacks.remove());
    assertEquals(new Event("1", null, "second event"), callbacks.remove());
  }

  @Test public void retry() throws IOException {
    consumeEvents(""
        + "retry: 22\n"
        + "\n"
        + "data: first event\n"
        + "id: 1\n"
        + "\n");
    assertEquals(22L, callbacks.remove());
    assertEquals(new Event("1", null, "first event"), callbacks.remove());
  }

  @Test public void retryInvalidFormatIgnored() throws IOException {
    consumeEvents(""
        + "retry: 22\n"
        + "\n"
        + "retry: hey"
        + "\n");
    assertEquals(22L, callbacks.remove());
  }
  
  private void consumeEvents(String source) throws IOException {
    ServerSentEventReader.Callback callback = new ServerSentEventReader.Callback() {
      @Override public void onEvent(@Nullable String id, @Nullable String type, String data) {
        callbacks.add(new Event(id, type, data));
      }
      @Override public void onRetryChange(long timeMs) {
        callbacks.add(timeMs);
      }
    };
    Buffer buffer = new Buffer().writeUtf8(source);
    ServerSentEventReader reader = new ServerSentEventReader(buffer, callback);
    while (reader.processNextEvent());
    assertEquals("Unconsumed buffer: " + buffer.readUtf8(), 0, buffer.size());
  }
}
