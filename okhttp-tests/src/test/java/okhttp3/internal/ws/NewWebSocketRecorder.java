/*
 * Copyright (C) 2016 Square, Inc.
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
import okhttp3.NewWebSocket;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.internal.Util;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class NewWebSocketRecorder extends NewWebSocket.Listener {
  private final String name;
  private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
  private NewWebSocket.Listener delegate;

  public NewWebSocketRecorder(String name) {
    this.name = name;
  }

  /** Sets a delegate for handling the next callback to this listener. Cleared after invoked. */
  public void setNextEventDelegate(NewWebSocket.Listener delegate) {
    this.delegate = delegate;
  }

  @Override public void onOpen(NewWebSocket webSocket, Response response) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onOpen", null);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onOpen(webSocket, response);
    } else {
      events.add(new Open(webSocket, response));
    }
  }

  @Override public void onMessage(NewWebSocket webSocket, ByteString bytes) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onMessage", null);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onMessage(webSocket, bytes);
    } else {
      Message event = new Message(bytes);
      events.add(event);
    }
  }

  @Override public void onMessage(NewWebSocket webSocket, String text) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onMessage", null);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onMessage(webSocket, text);
    } else {
      Message event = new Message(text);
      events.add(event);
    }
  }

  @Override public void onClosing(NewWebSocket webSocket, int code, String reason) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onClose " + code, null);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onClosing(webSocket, code, reason);
    } else {
      events.add(new Closing(code, reason));
    }
  }

  @Override public void onClosed(NewWebSocket webSocket, int code, String reason) {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onClose " + code, null);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onClosed(webSocket, code, reason);
    } else {
      events.add(new Closed(code, reason));
    }
  }

  @Override public void onFailure(NewWebSocket webSocket, Throwable t, Response response)  {
    Platform.get().log(Platform.INFO, "[WS " + name + "] onFailure", t);

    NewWebSocket.Listener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onFailure(webSocket, t, response);
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
    Object actual = nextEvent();
    assertEquals(new Message(payload), actual);
  }

  public void assertBinaryMessage(byte[] payload) {
    Object actual = nextEvent();
    assertEquals(new Message(ByteString.of(payload)), actual);
  }

  public void assertPong(ByteString payload) {
    Object actual = nextEvent();
    assertEquals(new Pong(payload), actual);
  }

  public void assertClose(int code, String reason) {
    Object actual = nextEvent();
    assertEquals(new Closing(code, reason), actual);
  }

  public void assertExhausted() {
    assertTrue("Remaining events: " + events, events.isEmpty());
  }

  public NewWebSocket assertOpen() {
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
      assertEquals(body, failure.responseBody);
    }
    assertEquals(cls, failure.t.getClass());
    assertEquals(message, failure.t.getMessage());
  }

  static final class Open {
    final NewWebSocket webSocket;
    final Response response;

    Open(NewWebSocket webSocket, Response response) {
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

  static final class Message {
    public final ByteString bytes;
    public final String string;

    public Message(ByteString bytes) {
      this.bytes = bytes;
      this.string = null;
    }

    public Message(String string) {
      this.bytes = null;
      this.string = string;
    }

    @Override public String toString() {
      return "Message[" + (bytes != null ? bytes : string) + "]";
    }

    @Override public int hashCode() {
      return (bytes != null ? bytes : string).hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof Message
          && Util.equal(((Message) other).bytes, bytes)
          && Util.equal(((Message) other).string, string);
    }
  }

  static final class Pong {
    public final ByteString payload;

    Pong(ByteString payload) {
      this.payload = payload;
    }

    @Override public String toString() {
      return "Pong[" + payload + "]";
    }

    @Override public int hashCode() {
      return payload.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Pong) {
        Pong other = (Pong) obj;
        return payload == null ? other.payload == null : payload.equals(other.payload);
      }
      return false;
    }
  }

  static final class Closing {
    public final int code;
    public final String reason;

    Closing(int code, String reason) {
      this.code = code;
      this.reason = reason;
    }

    @Override public String toString() {
      return "Closing[" + code + " " + reason + "]";
    }

    @Override public int hashCode() {
      return code * 37 + reason.hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof Closing
          && ((Closing) other).code == code
          && ((Closing) other).reason.equals(reason);
    }
  }

  static final class Closed {
    public final int code;
    public final String reason;

    Closed(int code, String reason) {
      this.code = code;
      this.reason = reason;
    }

    @Override public String toString() {
      return "Closed[" + code + " " + reason + "]";
    }

    @Override public int hashCode() {
      return code * 37 + reason.hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof Closed
          && ((Closed) other).code == code
          && ((Closed) other).reason.equals(reason);
    }
  }

  /** Expose this recorder as a frame callback and shim in "ping" events. */
  WebSocketReader.FrameCallback asFrameCallback() {
    return new WebSocketReader.FrameCallback() {
      @Override public void onReadMessage(ResponseBody body) throws IOException {
        if (body.contentType().equals(WebSocket.TEXT)) {
          String text = body.source().readUtf8();
          onMessage(null, text);
        } else if (body.contentType().equals(WebSocket.BINARY)) {
          ByteString bytes = body.source().readByteString();
          onMessage(null, bytes);
        } else {
          throw new IllegalArgumentException();
        }
      }

      @Override public void onReadPing(ByteString payload) {
        events.add(new Ping(payload));
      }

      @Override public void onReadPong(ByteString padload) {
      }

      @Override public void onReadClose(int code, String reason) {
        onClosing(null, code, reason);
      }
    };
  }

  void assertPing(ByteString payload) {
    Object actual = nextEvent();
    assertEquals(new Ping(payload), actual);
  }

  static final class Ping {
    public final ByteString buffer;

    Ping(ByteString buffer) {
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
