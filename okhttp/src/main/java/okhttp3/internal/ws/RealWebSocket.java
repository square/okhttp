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
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.NamedRunnable;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketReader.FrameCallback;

public abstract class RealWebSocket implements WebSocket, FrameCallback {
  private static final int CLOSE_PROTOCOL_EXCEPTION = 1002;

  private final Executor replyExecutor;
  private final WebSocketListener listener;
  private final String url;

  private final WebSocketWriter writer;
  private final WebSocketReader reader;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private volatile boolean writerSentClose;
  /** True after {@link IOException}. {@link #close(int, String)} becomes only valid call. */
  private boolean writerWantsClose;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private boolean readerSentClose;

  /** True after calling {@link #close()} to free connection resources. */
  private final AtomicBoolean connectionClosed = new AtomicBoolean();

  protected RealWebSocket(boolean isClient, BufferedSource source, BufferedSink sink, Random random,
      Executor replyExecutor, WebSocketListener listener, String url) {
    this.replyExecutor = replyExecutor;
    this.listener = listener;
    this.url = url;

    writer = new WebSocketWriter(isClient, sink, random);
    reader = new WebSocketReader(isClient, source, this);
  }

  @Override public final void onMessage(ResponseBody message) throws IOException {
    listener.onMessage(message);
  }

  @Override public final void onPing(final Buffer buffer) {
    replyExecutor.execute(new NamedRunnable("OkHttp %s WebSocket Pong Reply", url) {
      @Override protected void execute() {
        peerPing(buffer);
      }
    });
  }

  @Override public final void onPong(Buffer buffer) {
    listener.onPong(buffer);
  }

  @Override public final void onClose(final int code, final String reason) {
    readerSentClose = true;
    replyExecutor.execute(new NamedRunnable("OkHttp %s WebSocket Close Reply", url) {
      @Override protected void execute() {
        peerClose(code, reason);
      }
    });
  }

  /**
   * Read a single message from the web socket and deliver it to the listener. This method should be
   * called in a loop with the return value indicating whether looping should continue.
   */
  public final boolean readMessage() {
    try {
      reader.processNextFrame();
      return !readerSentClose;
    } catch (IOException e) {
      readerErrorClose(e);
      return false;
    }
  }

  @Override public final void sendMessage(RequestBody message) throws IOException {
    if (message == null) throw new NullPointerException("message == null");
    if (writerSentClose) throw new IllegalStateException("closed");
    if (writerWantsClose) throw new IllegalStateException("must call close()");

    MediaType contentType = message.contentType();
    if (contentType == null) {
      throw new IllegalArgumentException(
          "Message content type was null. Must use WebSocket.TEXT or WebSocket.BINARY.");
    }
    String contentSubtype = contentType.subtype();

    int formatOpcode;
    if (WebSocket.TEXT.subtype().equals(contentSubtype)) {
      formatOpcode = OPCODE_TEXT;
    } else if (WebSocket.BINARY.subtype().equals(contentSubtype)) {
      formatOpcode = OPCODE_BINARY;
    } else {
      throw new IllegalArgumentException("Unknown message content type: "
          + contentType.type() + "/" + contentType.subtype() // Omit any implicitly added charset.
          + ". Must use WebSocket.TEXT or WebSocket.BINARY.");
    }

    BufferedSink sink = Okio.buffer(writer.newMessageSink(formatOpcode, message.contentLength()));
    try {
      message.writeTo(sink);
      sink.close();
    } catch (IOException e) {
      writerWantsClose = true;
      throw e;
    }
  }

  @Override public final void sendPing(Buffer payload) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");
    if (writerWantsClose) throw new IllegalStateException("must call close()");

    try {
      writer.writePing(payload);
    } catch (IOException e) {
      writerWantsClose = true;
      throw e;
    }
  }

  /** Send an unsolicited pong with the specified payload. */
  final void sendPong(Buffer payload) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");
    if (writerWantsClose) throw new IllegalStateException("must call close()");

    try {
      writer.writePong(payload);
    } catch (IOException e) {
      writerWantsClose = true;
      throw e;
    }
  }

  @Override public final void close(int code, String reason) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");
    writerSentClose = true;

    try {
      writer.writeClose(code, reason);
    } catch (IOException e) {
      if (connectionClosed.compareAndSet(false, true)) {
        // Try to close the connection without masking the original exception.
        try {
          close();
        } catch (IOException ignored) {
        }
      }
      throw e;
    }
  }

  /** Replies with a pong when a ping frame is read from the peer. */
  void peerPing(Buffer payload) {
    try {
      writer.writePong(payload);
    } catch (IOException ignored) {
    }
  }

  /** Replies and closes this web socket when a close frame is read from the peer. */
  void peerClose(int code, String reason) {
    if (!writerSentClose) {
      try {
        writer.writeClose(code, reason);
      } catch (IOException ignored) {
      }
    }

    if (connectionClosed.compareAndSet(false, true)) {
      try {
        close();
      } catch (IOException ignored) {
      }
    }

    listener.onClose(code, reason);
  }

  /** Called on the reader thread when an error occurs. */
  private void readerErrorClose(IOException e) {
    // For protocol exceptions, try to inform the server of such.
    if (!writerSentClose && e instanceof ProtocolException) {
      try {
        writer.writeClose(CLOSE_PROTOCOL_EXCEPTION, null);
      } catch (IOException ignored) {
      }
    }

    if (connectionClosed.compareAndSet(false, true)) {
      try {
        close();
      } catch (IOException ignored) {
      }
    }

    listener.onFailure(e, null);
  }

  /** Perform any tear-down work (close the connection, shutdown executors). */
  protected abstract void close() throws IOException;
}
