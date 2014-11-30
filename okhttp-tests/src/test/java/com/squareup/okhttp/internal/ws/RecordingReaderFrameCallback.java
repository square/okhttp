/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.ws;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import okio.Buffer;

import static org.junit.Assert.assertEquals;

public final class RecordingReaderFrameCallback implements WebSocketReader.FrameCallback {
  private static class Ping {
    public final Buffer buffer;

    private Ping(Buffer buffer) {
      this.buffer = buffer;
    }
  }

  private static class Close {
    public final Buffer buffer;

    private Close(Buffer buffer) {
      this.buffer = buffer;
    }
  }

  private final Deque<Object> events = new ArrayDeque<>();

  @Override public void onPing(Buffer buffer) {
    events.add(new Ping(buffer));
  }

  @Override public void onClose(Buffer buffer) throws IOException {
    events.add(new Close(buffer));
  }

  public void assertPing(Buffer payload) {
    Ping ping = (Ping) events.removeFirst();
    assertEquals(payload, ping.buffer);
  }

  public void assertClose(Buffer payload) {
    Close close = (Close) events.removeFirst();
    assertEquals(payload, close.buffer);
  }
}
