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
package com.squareup.okhttp.ws;

import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.ws.WebSocketReader;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSource;

import static com.squareup.okhttp.ws.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.ws.WebSocket.PayloadType.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class WebSocketRecorder implements WebSocketReader.FrameCallback, WebSocketListener {
  public interface MessageDelegate {
    void onMessage(BufferedSource payload, WebSocket.PayloadType type) throws IOException;
  }

  private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
  private MessageDelegate delegate;

  /** Sets a delegate for the next call to {@link #onMessage}. Cleared after invoked. */
  public void setNextMessageDelegate(MessageDelegate delegate) {
    this.delegate = delegate;
  }

  @Override public void onOpen(WebSocket webSocket, Response response) {
  }

  @Override public void onMessage(BufferedSource source, WebSocket.PayloadType type)
      throws IOException {
    if (delegate != null) {
      delegate.onMessage(source, type);
      delegate = null;
    } else {
      Message message = new Message(type);
      source.readAll(message.buffer);
      source.close();
      events.add(message);
    }
  }

  @Override public void onPing(Buffer buffer) {
    events.add(new Ping(buffer));
  }

  @Override public void onPong(Buffer buffer) {
    events.add(new Pong(buffer));
  }

  @Override public void onClose(int code, String reason) {
    events.add(new Close(code, reason));
  }

  @Override public void onFailure(IOException e, Response response) {
    events.add(e);
  }

  private Object nextEvent() {
    try {
      Object event = events.poll(10, TimeUnit.SECONDS);
      if (event == null) {
        throw new AssertionError("Timed out.");
      }
      return event;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void assertTextMessage(String payload) {
    Message message = new Message(TEXT);
    message.buffer.writeUtf8(payload);
    assertEquals(message, nextEvent());
  }

  public void assertBinaryMessage(byte[] payload) {
    Message message = new Message(BINARY);
    message.buffer.write(payload);
    assertEquals(message, nextEvent());
  }

  public void assertPing(Buffer payload) {
    assertEquals(new Ping(payload), nextEvent());
  }

  public void assertPong(Buffer payload) {
    assertEquals(new Pong(payload), nextEvent());
  }

  public void assertClose(int code, String reason) {
    assertEquals(new Close(code, reason), nextEvent());
  }

  public void assertFailure(Class<? extends IOException> cls, String message) {
    Object event = nextEvent();
    String errorMessage =
        "Expected [" + cls.getName() + ": " + message + "] but was [" + event + "].";
    assertNotNull(errorMessage, event);
    assertEquals(errorMessage, cls, event.getClass());
    assertEquals(errorMessage, cls.cast(event).getMessage(), message);
  }

  public void assertExhausted() {
    assertTrue("Remaining events: " + events, events.isEmpty());
  }

  private static class Message {
    public final WebSocket.PayloadType type;
    public final Buffer buffer = new Buffer();

    private Message(WebSocket.PayloadType type) {
      this.type = type;
    }

    @Override public String toString() {
      return "Message[" + type + " " + buffer + "]";
    }

    @Override public int hashCode() {
      return type.hashCode() * 37 + buffer.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Message) {
        Message other = (Message) obj;
        return type == other.type && buffer.equals(other.buffer);
      }
      return false;
    }
  }

  private static class Ping {
    public final Buffer buffer;

    private Ping(Buffer buffer) {
      this.buffer = buffer;
    }

    @Override public String toString() {
      return "Ping[" + buffer + "]";
    }

    @Override public int hashCode() {
      return buffer.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Ping) {
        Ping other = (Ping) obj;
        return buffer == null ? other.buffer == null : buffer.equals(other.buffer);
      }
      return false;
    }
  }

  private static class Pong {
    public final Buffer buffer;

    private Pong(Buffer buffer) {
      this.buffer = buffer;
    }

    @Override public String toString() {
      return "Pong[" + buffer + "]";
    }

    @Override public int hashCode() {
      return buffer.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Pong) {
        Pong other = (Pong) obj;
        return buffer == null ? other.buffer == null : buffer.equals(other.buffer);
      }
      return false;
    }
  }

  private static class Close {
    public final int code;
    public final String reason;

    private Close(int code, String reason) {
      this.code = code;
      this.reason = reason;
    }

    @Override public String toString() {
      return "Close[" + code + " " + reason + "]";
    }

    @Override public int hashCode() {
      return code * 37 + reason.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Close) {
        Close other = (Close) obj;
        return code == other.code && reason.equals(other.reason);
      }
      return false;
    }
  }
}
