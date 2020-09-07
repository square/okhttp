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
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

import static org.assertj.core.api.Assertions.assertThat;

public final class WebSocketRecorder extends WebSocketListener {
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
    Platform.get().log("[WS " + name + "] onOpen", Platform.INFO, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onOpen(webSocket, response);
    } else {
      events.add(new Open(webSocket, response));
    }
  }

  @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
    Platform.get().log("[WS " + name + "] onMessage", Platform.INFO, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onMessage(webSocket, bytes);
    } else {
      Message event = new Message(bytes);
      events.add(event);
    }
  }

  @Override public void onMessage(WebSocket webSocket, String text) {
    Platform.get().log("[WS " + name + "] onMessage", Platform.INFO, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onMessage(webSocket, text);
    } else {
      Message event = new Message(text);
      events.add(event);
    }
  }

  @Override public void onClosing(WebSocket webSocket, int code, String reason) {
    Platform.get().log("[WS " + name + "] onClosing " + code, Platform.INFO, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onClosing(webSocket, code, reason);
    } else {
      events.add(new Closing(code, reason));
    }
  }

  @Override public void onClosed(WebSocket webSocket, int code, String reason) {
    Platform.get().log("[WS " + name + "] onClosed " + code, Platform.INFO, null);

    WebSocketListener delegate = this.delegate;
    if (delegate != null) {
      this.delegate = null;
      delegate.onClosed(webSocket, code, reason);
    } else {
      events.add(new Closed(code, reason));
    }
  }

  @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response)  {
    Platform.get().log("[WS " + name + "] onFailure", Platform.INFO, t);

    WebSocketListener delegate = this.delegate;
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
    assertThat(actual).isEqualTo(new Message(payload));
  }

  public void assertBinaryMessage(ByteString payload) {
    Object actual = nextEvent();
    assertThat(actual).isEqualTo(new Message(payload));
  }

  public void assertPing(ByteString payload) {
    Object actual = nextEvent();
    assertThat(actual).isEqualTo(new Ping(payload));
  }

  public void assertPong(ByteString payload) {
    Object actual = nextEvent();
    assertThat(actual).isEqualTo(new Pong(payload));
  }

  public void assertClosing(int code, String reason) {
    Object actual = nextEvent();
    assertThat(actual).isEqualTo(new Closing(code, reason));
  }

  public void assertClosed(int code, String reason) {
    Object actual = nextEvent();
    assertThat(actual).isEqualTo(new Closed(code, reason));
  }

  public void assertExhausted() {
    assertThat(events).isEmpty();
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
    assertThat(failure.response).isNull();
    assertThat(failure.t).isSameAs(t);
  }

  public void assertFailure(Class<? extends IOException> cls, String... messages) {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    Failure failure = (Failure) event;
    assertThat(failure.response).isNull();
    assertThat(failure.t.getClass()).isEqualTo(cls);
    if (messages.length > 0) {
      assertThat(messages).contains(failure.t.getMessage());
    }
  }

  public void assertFailure() {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
  }

  public void assertFailure(int code, String body, Class<? extends IOException> cls, String message)
      throws IOException {
    Object event = nextEvent();
    if (!(event instanceof Failure)) {
      throw new AssertionError("Expected Failure but was " + event);
    }
    Failure failure = (Failure) event;
    assertThat(failure.response.code()).isEqualTo(code);
    if (body != null) {
      assertThat(failure.responseBody).isEqualTo(body);
    }
    assertThat(failure.t.getClass()).isEqualTo(cls);
    assertThat(failure.t.getMessage()).isEqualTo(message);
  }

  /** Expose this recorder as a frame callback and shim in "ping" events. */
  public WebSocketReader.FrameCallback asFrameCallback() {
    return new WebSocketReader.FrameCallback() {
      @Override public void onReadMessage(String text) throws IOException {
        onMessage(null, text);
      }

      @Override public void onReadMessage(ByteString bytes) throws IOException {
        onMessage(null, bytes);
      }

      @Override public void onReadPing(ByteString payload) {
        events.add(new Ping(payload));
      }

      @Override public void onReadPong(ByteString payload) {
        events.add(new Pong(payload));
      }

      @Override public void onReadClose(int code, String reason) {
        onClosing(null, code, reason);
      }
    };
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
          && Objects.equals(((Message) other).bytes, bytes)
          && Objects.equals(((Message) other).string, string);
    }
  }

  static final class Ping {
    public final ByteString payload;

    public Ping(ByteString payload) {
      this.payload = payload;
    }

    @Override public String toString() {
      return "Ping[" + payload + "]";
    }

    @Override public int hashCode() {
      return payload.hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof Ping
          && ((Ping) other).payload.equals(payload);
    }
  }

  static final class Pong {
    public final ByteString payload;

    public Pong(ByteString payload) {
      this.payload = payload;
    }

    @Override public String toString() {
      return "Pong[" + payload + "]";
    }

    @Override public int hashCode() {
      return payload.hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof Pong
          && ((Pong) other).payload.equals(payload);
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
}
