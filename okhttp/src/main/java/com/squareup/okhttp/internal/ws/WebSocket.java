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

import com.squareup.okhttp.internal.NamedRunnable;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import okio.Buffer;
import okio.BufferedSink;

import static com.squareup.okhttp.internal.ws.WebSocketReader.FrameCallback;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Blocking interface to connect and write to a web socket. */
public abstract class WebSocket {
  /** A close code which indicates that the peer encountered a protocol exception. */
  private static final int CLOSE_PROTOCOL_EXCEPTION = 1002;

  /** The format of a message payload. */
  public enum PayloadType {
    /** UTF8-encoded text data. */
    TEXT,
    /** Arbitrary binary data. */
    BINARY
  }

  private final WebSocketWriter writer;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private volatile boolean writerClosed;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private volatile boolean readerClosed;
  /** Lock required to close the connection. */
  private final Object closeLock = new Object();

  WebSocket(String name, final WebSocketReader reader, final WebSocketWriter writer) {
    this.writer = writer;

    // Pings come in on the reader thread. This executor contends with callers for writing pongs.
    final ThreadPoolExecutor pongExecutor =
        new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>());
    pongExecutor.allowCoreThreadTimeOut(true);

    reader.setFrameCallback(new FrameCallback() {
      @Override public void onPing(final Buffer buffer) {
        pongExecutor.execute(new NamedRunnable("WebSocket PongWriter") {
          @Override protected void execute() {
            try {
              writer.writePong(buffer);
            } catch (IOException ignored) {
            }
          }
        });
      }

      @Override public void onClose(Buffer buffer) throws IOException {
        peerClose(buffer);
      }
    });

    new Thread(new Runnable() {
      @Override public void run() {
        while (!readerClosed) {
          try {
            reader.readMessage();
          } catch (IOException e) {
            readerErrorClose(e, reader.listener());
            return;
          }
        }
      }
    }, "WebSocketReader " + name).start();
  }

  /**
   * Stream a message payload to the server of the specified {code type}.
   * <p>
   * You must call {@link BufferedSink#close() close()} to complete the message. Calls to
   * {@link BufferedSink#flush() flush()} write a frame fragment. The message may be empty.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  public BufferedSink newMessageSink(PayloadType type) {
    if (writerClosed) throw new IllegalStateException("Closed");
    return writer.newMessageSink(type);
  }

  /**
   * Send a message payload the server of the specified {@code type}.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  public void sendMessage(PayloadType type, Buffer payload) throws IOException {
    if (writerClosed) throw new IllegalStateException("Closed");
    writer.sendMessage(type, payload);
  }

  /**
   * Send a close frame to the server.
   * <p>
   * The corresponding {@link WebSocketListener} will continue to get messages until its
   * {@link WebSocketListener#onClose onClose()} method is called.
   * <p>
   * It is an error to call this method before calling close on an active writer. Calling this
   * method more than once has no effect.
   */
  public void close(int code, String reason) throws IOException {
    boolean closeConnection;
    synchronized (closeLock) {
      if (writerClosed) return;
      writerClosed = true;

      // If the reader has also indicated a desire to close we will close the connection.
      closeConnection = readerClosed;
    }

    writer.writeClose(code, reason);

    if (closeConnection) {
      closeConnection();
    }
  }

  /** Called on the reader thread when a close frame is encountered. */
  private void peerClose(Buffer buffer) throws IOException {
    boolean closeConnection;
    synchronized (closeLock) {
      readerClosed = true;

      // If the writer has already indicated a desire to close we will close the connection.
      closeConnection = writerClosed;
      writerClosed = true;
    }

    if (closeConnection) {
      closeConnection();
    } else {
      // The reader thread will read no more frames so use it to send the response.
      writer.writeClose(buffer);
    }
  }

  /** Called on the reader thread when an error occurs. */
  private void readerErrorClose(IOException e, WebSocketListener listener) {
    boolean closeConnection;
    synchronized (closeLock) {
      readerClosed = true;

      // If the writer has not closed we will close the connection.
      closeConnection = !writerClosed;
      writerClosed = true;
    }

    if (closeConnection) {
      if (e instanceof ProtocolException) {
        // For protocol exceptions, try to inform the server of such.
        try {
          writer.writeClose(CLOSE_PROTOCOL_EXCEPTION, null);
        } catch (IOException ignored) {
        }
      }

      try {
        closeConnection();
      } catch (IOException ignored) {
      }
    }

    listener.onFailure(e);
  }

  /** Perform any tear-down work on the connection (close the socket, recycle, etc.). */
  protected abstract void closeConnection() throws IOException;

  /**
   * True if this web socket is closed and can no longer be written to.
   * <p>
   * Note: Due to the asynchronous nature of a websocket, a {@code true} value from method does not
   * guarantee that the connection will be open or even that you will be able to write in a
   * subsequent call.
   */
  public boolean isClosed() {
    return writerClosed;
  }
}
