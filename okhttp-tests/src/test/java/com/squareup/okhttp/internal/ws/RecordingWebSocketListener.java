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

import com.squareup.okhttp.WebSocketListener;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import okio.Buffer;
import okio.BufferedSource;

import static com.squareup.okhttp.WebSocket.PayloadType;
import static org.junit.Assert.assertEquals;

public final class RecordingWebSocketListener implements WebSocketListener {
  public interface MessageDelegate {
    void onMessage(BufferedSource payload, PayloadType type) throws IOException;
  }

  public static class Message {
    public final Buffer buffer = new Buffer();
    public final PayloadType type;

    public Message(PayloadType type) {
      this.type = type;
    }
  }

  public static class Close {
    public final int code;
    public final String reason;

    public Close(int code, String reason) {
      this.code = code;
      this.reason = reason;
    }
  }

  private final Deque<Object> events = new ArrayDeque<>();

  private MessageDelegate delegate;

  /** Sets a delegate for the next call to {@link #onMessage}. Cleared after invoked. */
  public void setNextMessageDelegate(MessageDelegate delegate) {
    this.delegate = delegate;
  }

  @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
    if (delegate != null) {
      delegate.onMessage(payload, type);
      delegate = null;
    } else {
      Message message = new Message(type);
      payload.readAll(message.buffer);
      payload.close();
      events.add(message);
    }
  }

  @Override public void onClose(int code, String reason) {
    events.add(new Close(code, reason));
  }

  @Override public void onFailure(IOException e) {
    events.add(e);
  }

  public void assertTextMessage(String payload) throws IOException {
    Message message = (Message) events.removeFirst();
    assertEquals(payload, message.buffer.readUtf8());
  }

  public void assertBinaryMessage(byte[] payload) {
    Message message = (Message) events.removeFirst();
    assertEquals(new Buffer().write(payload), message.buffer);
  }
}
