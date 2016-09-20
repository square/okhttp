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
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.Util;
import okhttp3.internal.platform.Platform;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import static okhttp3.internal.platform.Platform.INFO;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_ABNORMAL_TERMINATION;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_CLIENT_GOING_AWAY;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_PROTOCOL_EXCEPTION;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketReader.FrameCallback;

/**
 * An implementation of {@link WebSocket} which sits on top of {@link WebSocketReader} and
 * {@link WebSocketWriter}.
 *
 * <h2>Threading</h2>
 * This class deals with three threads concurrently and care must be taken to only access the
 * appropriate resources on each:
 * <ul>
 * <li><b>Reader</b>: This is the only thread allowed to access {@link #reader}. Methods from
 * {@link FrameCallback} will happen on this thread as a result. This is the only thread that
 * should invoke methods on the {@link #readerListener}.</li>
 * <li><b>Replier</b>: Invoked on {@link #replier} to write responses from reading
 * frames. Contends with the "Sender" thread for access to {@link #writer}.</li>
 * <li><b>Sender</b>: Methods from {@link WebSocket} will happen on this thread. Contends with the
 * "Replier" thread</li>
 * </ul>
 * Instance variables have prefixes matching the thread names based on the thread on which they can
 * be accessed. A prefix of "writer" indicates both "Sender" and "Replier" threads can access.
 */
public abstract class RealWebSocket implements WebSocket, FrameCallback {
  private final WebSocketReader reader;
  private final WebSocketListener readerListener;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private boolean readerSawClose;

  final WebSocketWriter writer;
  /** True after calling {@link WebSocketWriter#writeClose(int, String)} to send a close frame. */
  final AtomicBoolean writerClosed = new AtomicBoolean();

  /** Guarded by itself. Must check {@link #isShutdown} before enqueuing work. */
  private final Executor replier;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private boolean senderSentClose;
  /** True after {@link IOException}. {@link #close(int, String)} becomes only valid call. */
  private boolean senderWantsClose;

  private final Response response;
  private final String name;

  /** Guarded by {@link #replier}. True after calling {@link #shutdown()}. */
  private boolean isShutdown;

  protected RealWebSocket(boolean isClient, BufferedSource source, BufferedSink sink, Random random,
      Executor replier, WebSocketListener readerListener, Response response, String name) {
    this.readerListener = readerListener;
    this.replier = replier;
    this.response = response;
    this.name = name;

    reader = new WebSocketReader(isClient, source, this);
    writer = new WebSocketWriter(isClient, sink, random);
  }

  ////// READER THREAD

  /** Read and process all socket messages delivering callbacks to the supplied listener. */
  public final void loopReader() {
    try {
      readerListener.onOpen(this, response);
    } catch (Throwable t) {
      Util.throwIfFatal(t);
      replyToReaderError(t);
      readerListener.onFailure(t, null);
      return;
    }

    while (processNextFrame()) {
    }
  }

  /**
   * Read a single control frame or all frames of a message from the web socket and deliver any
   * notifications to the listener. Returns false when no more messages can be read.
   */
  final boolean processNextFrame() {
    try {
      // This method call results in one or more onRead* methods being called on this thread.
      reader.processNextFrame();

      return !readerSawClose;
    } catch (Throwable t) {
      Util.throwIfFatal(t);
      replyToReaderError(t);
      if (t instanceof IOException && !(t instanceof ProtocolException)) {
        readerListener.onClose(CLOSE_ABNORMAL_TERMINATION, "");
      } else {
        readerListener.onFailure(t, null);
      }
      return false;
    }
  }

  @Override public final void onReadMessage(ResponseBody message) throws IOException {
    readerListener.onMessage(message);
  }

  @Override public final void onReadPing(ByteString buffer) {
    replyToPeerPing(buffer);
  }

  @Override public final void onReadPong(ByteString buffer) {
    readerListener.onPong(buffer);
  }

  @Override public final void onReadClose(int code, String reason) {
    replyToPeerClose(code, reason);
    readerSawClose = true;
    readerListener.onClose(code, reason);
  }

  ///// REPLIER THREAD (executed on replier, contends with sender thread)

  /** Replies with a pong when a ping frame is read from the peer. */
  private void replyToPeerPing(final ByteString payload) {
    Runnable replierPong = new NamedRunnable("OkHttp %s WebSocket Pong Reply", name) {
      @Override protected void execute() {
        try {
          writer.writePong(payload);
        } catch (IOException t) {
          Platform.get().log(INFO, "Unable to send pong reply in response to peer ping.", t);
        }
      }
    };
    synchronized (replier) {
      if (!isShutdown) {
        replier.execute(replierPong);
      }
    }
  }

  /** Replies and closes this web socket when a close frame is read from the peer. */
  private void replyToPeerClose(final int code, final String reason) {
    Runnable replierClose = new NamedRunnable("OkHttp %s WebSocket Close Reply", name) {
      @Override protected void execute() {
        if (writerClosed.compareAndSet(false, true)) {
          try {
            writer.writeClose(code, reason);
          } catch (IOException t) {
            Platform.get().log(INFO, "Unable to send close reply in response to peer close.", t);
          }
        }

        quietlyCloseConnection();
      }
    };
    synchronized (replier) {
      if (!isShutdown) {
        replier.execute(replierClose);
      }
    }
  }

  private void replyToReaderError(final Throwable t) {
    Runnable replierClose = new NamedRunnable("OkHttp %s WebSocket Fatal Reply", name) {
      @Override protected void execute() {
        if (writerClosed.compareAndSet(false, true)) {
          // For protocol and runtime exceptions, try to inform the server of such.
          boolean protocolException = t instanceof ProtocolException;
          boolean runtimeException = !(t instanceof IOException);
          if (protocolException || runtimeException) {
            int code = protocolException ? CLOSE_PROTOCOL_EXCEPTION : CLOSE_CLIENT_GOING_AWAY;
            try {
              writer.writeClose(code, null);
            } catch (IOException inner) {
              Platform.get()
                  .log(INFO, "Unable to send close in response to listener error.", inner);
            }
          }
        }

        quietlyCloseConnection();
      }
    };
    synchronized (replier) {
      if (!isShutdown) {
        replier.execute(replierClose);
      }
    }
  }

  ////// SENDER THREAD (aka user thread)

  @Override public final void sendMessage(RequestBody message) throws IOException {
    if (message == null) throw new NullPointerException("message == null");
    if (senderSentClose) throw new IllegalStateException("closed");
    if (senderWantsClose) throw new IllegalStateException("must call close()");

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
      senderWantsClose = true;
      throw e;
    }
  }

  @Override public final void sendPing(ByteString payload) throws IOException {
    if (payload == null) throw new NullPointerException("payload == null");
    if (senderSentClose) throw new IllegalStateException("closed");
    if (senderWantsClose) throw new IllegalStateException("must call close()");

    try {
      writer.writePing(payload);
    } catch (IOException e) {
      senderWantsClose = true;
      throw e;
    }
  }

  /** Send an unsolicited pong with the specified payload. */
  final void sendPong(ByteString payload) throws IOException {
    if (payload == null) throw new NullPointerException("payload == null");
    if (senderSentClose) throw new IllegalStateException("closed");
    if (senderWantsClose) throw new IllegalStateException("must call close()");

    try {
      writer.writePong(payload);
    } catch (IOException e) {
      senderWantsClose = true;
      throw e;
    }
  }

  @Override public final void close(int code, String reason) throws IOException {
    if (senderSentClose) throw new IllegalStateException("closed");
    senderSentClose = true;

    // Not doing a CAS because we want writer to throw if already closed via peer close.
    writerClosed.set(true);

    try {
      writer.writeClose(code, reason);
    } catch (IOException e) {
      quietlyCloseConnection();
      throw e;
    }

    // NOTE: We do not close the connection here! That will happen when we read the close reply.
  }

  ////// ANY THREAD

  void quietlyCloseConnection() {
    synchronized (replier) {
      if (isShutdown) return;
      isShutdown = true;
    }
    try {
      shutdown();
    } catch (Throwable inner) {
      Util.throwIfFatal(inner);
      Platform.get().log(INFO, "Unable to close web socket connection.", inner);
    }
  }

  /** Perform any tear-down work (close the connection, shutdown executors). */
  protected abstract void shutdown();
}
