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
package okhttp3.internal.ws;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.platform.Platform;
import okio.Buffer;

import static okhttp3.WebSocket.BINARY;
import static okhttp3.WebSocket.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class WebSocketRecorder implements WebSocketListener {
  private final String name;
  private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
  private WebSocketListener delegate;

  public WebSocketRecorder(String name) {
    this.name = name;
  }

  /** Sets a delegate for handling the next callback to this listener. Cleared after invoked. */
  public void setNextEventDelegate(WebSocketListener delegate) {
    this.delegate = delegate;
  }

  @Override public void onOpen(WebSocket webSocket, Response response) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onOpen", null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onOpen(webSocket, response);
    } else {
      events.add(new Open(webSocket, response));
    }
  }

  @Override public void onMessage(ResponseBody message) throws IOException {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onMessage", null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onMessage(message);
    } else {
      Message event = new Message(message.contentType());
      message.source().readAll(event.buffer);
      message.close();
      events.add(event);
    }
  }

  @Override public void onPong(Buffer buffer) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onPong", null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onPong(buffer);
    } else {
      events.add(new Pong(buffer));
    }
  }

  @Override public void onClose(int code, String reason) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onClose " + code, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onClose(code, reason);
    } else {
      events.add(new Close(code, reason));
    }
  }

  @Override public void onFailure(Throwable t, Response response) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onFailure", t);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onFailure(t, response);
    } else {
      events.add(new Failure(t, response));
    }
  }

  private Object nextEvent() {
    try {
      Object event = events.poll(10, TimeUnit.SECONDS);
      if (event == null) {
        throw new AssertionError("Timed out waiting for event.");
      }
      return event;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void assertTextMessage(String payload) {
    Message message = new Message(TEXT);
    message.buffer.writeUtf8(payload);
    Object actual = nextEvent();
    assertEquals(message, actual);
  }

  public void assertBinaryMessage(byte[] payload) {
    Message message = new Message(BINARY);
    message.buffer.write(payload);
    Object actual = nextEvent();
    assertEquals(message, actual);
  }

  public void assertPong(Buffer payload) {
    Object actual = nextEvent();
    assertEquals(new Pong(payload), actual);
  }

  public void assertClose(int code, String reason) {
    Object actual = nextEvent();
    assertEquals(new Close(code, reason), actual);
  }

  public void assertExhausted() {
    assertTrue("Remaining events: " + events, events.isEmpty());
  }

  public WebSocket assertOpen() {
    Object event = nextEvent();
    if (!(event instanceof Open)) {
      throw new AssertionError("Expected Open but was " + event);
    }
    return ((Open) event).webSocket;
  }

  public void assertFailure(Throwable t) {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    Failure failure = (Failure) event;
    assertNull(failure.response);
    assertSame(t, failure.t);
  }

  public void assertFailure(Class<? extends IOException> cls, String message) {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    Failure failure = (Failure) event;
    assertNull(failure.response);
    assertEquals(cls, failure.t.getClass());
    assertEquals(message, failure.t.getMessage());
  }

  public void assertFailure(int code, String body, Class<? extends IOException> cls, String message)
      throws IOException {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    Failure failure = (Failure) event;
    assertEquals(code, failure.response.code());
    if (body != null) {
      assertEquals(body, failure.response.body().string());
    }
    assertEquals(cls, failure.t.getClass());
    assertEquals(message, failure.t.getMessage());
  }

  static final class Open {
    final WebSocket webSocket;
    final Response response;

    Open(WebSocket webSocket, Response response) {
      this.webSocket = webSocket;
      this.response = response;
    }

    @Override public String toString() {
      return "Open[" + response + "]";
    }
  }

  static final class Failure {
    final Throwable t;
    final Response response;

    Failure(Throwable t, Response response) {
      this.t = t;
      this.response = response;
    }

    @Override public String toString() {
      if (response == null) {
        return "Failure[" + t + "]";
      }
      return "Failure[" + response + "]";
    }
  }

  static final class Message {
    public final MediaType mediaType;
    public final Buffer buffer = new Buffer();

    Message(MediaType mediaType) {
      this.mediaType = mediaType;
    }

    @Override public String toString() {
      return "Message[" + mediaType + " " + buffer + "]";
    }

    @Override public int hashCode() {
      return mediaType.hashCode() * 37 + buffer.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Message) {
        Message other = (Message) obj;
        return mediaType.equals(other.mediaType) && buffer.equals(other.buffer);
      }
      return false;
    }
  }

  static final class Pong {
    public final Buffer buffer;

    Pong(Buffer buffer) {
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

  static final class Close {
    public final int code;
    public final String reason;

    Close(int code, String reason) {
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

  /** Expose this recorder as a frame callback and shim in "ping" events. */
  WebSocketReader.FrameCallback asFrameCallback() {
    return new WebSocketReader.FrameCallback() {
      @Override public void onReadMessage(ResponseBody body) throws IOException {
        onMessage(body);
      }

      @Override public void onReadPing(Buffer buffer) {
        events.add(new Ping(buffer));
      }

      @Override public void onReadPong(Buffer buffer) {
        onPong(buffer);
      }

      @Override public void onReadClose(int code, String reason) {
        onClose(code, reason);
      }
    };
  }

  void assertPing(Buffer payload) {
    Object actual = nextEvent();
    assertEquals(new Ping(payload), actual);
  }

  static final class Ping {
    public final Buffer buffer;

    Ping(Buffer buffer) {
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
}
