/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal.http2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Headers;
import okhttp3.internal.Util;
import okio.AsyncTimeout;
import okio.Buffer;
import okio.BufferedSource;
import okio.Sink;
import okio.Source;
import okio.Timeout;

/** A logical bidirectional stream. */
public final class Http2Stream {
  // Internal state is guarded by this. No long-running or potentially
  // blocking operations are performed while the lock is held.

  /**
   * The total number of bytes consumed by the application (with {@link FramingSource#read}), but
   * not yet acknowledged by sending a {@code WINDOW_UPDATE} frame on this stream.
   */
  // Visible for testing
  long unacknowledgedBytesRead = 0;

  /**
   * Count of bytes that can be written on the stream before receiving a window update. Even if this
   * is positive, writes will block until there available bytes in {@code
   * connection.bytesLeftInWriteWindow}.
   */
  // guarded by this
  long bytesLeftInWriteWindow;

  final int id;
  final Http2Connection connection;

  /**
   * Received headers yet to be {@linkplain #takeHeaders taken}, or {@linkplain FramingSource#read
   * read}.
   */
  private final Deque<Headers> headersQueue = new ArrayDeque<>();
  private Header.Listener headersListener;

  /** True if response headers have been sent or received. */
  private boolean hasResponseHeaders;

  private final FramingSource source;
  final FramingSink sink;
  final StreamTimeout readTimeout = new StreamTimeout();
  final StreamTimeout writeTimeout = new StreamTimeout();

  /**
   * The reason why this stream was abnormally closed. If there are multiple reasons to abnormally
   * close this stream (such as both peers closing it near-simultaneously) then this is the first
   * reason known to this peer.
   */
  ErrorCode errorCode = null;

  Http2Stream(int id, Http2Connection connection, boolean outFinished, boolean inFinished,
      @Nullable Headers headers) {
    if (connection == null) throw new NullPointerException("connection == null");

    this.id = id;
    this.connection = connection;
    this.bytesLeftInWriteWindow =
        connection.peerSettings.getInitialWindowSize();
    this.source = new FramingSource(connection.okHttpSettings.getInitialWindowSize());
    this.sink = new FramingSink();
    this.source.finished = inFinished;
    this.sink.finished = outFinished;
    if (headers != null) {
      headersQueue.add(headers);
    }

    if (isLocallyInitiated() && headers != null) {
      throw new IllegalStateException("locally-initiated streams shouldn't have headers yet");
    } else if (!isLocallyInitiated() && headers == null) {
      throw new IllegalStateException("remotely-initiated streams should have headers");
    }
  }

  public int getId() {
    return id;
  }

  /**
   * Returns true if this stream is open. A stream is open until either:
   *
   * <ul>
   *     <li>A {@code SYN_RESET} frame abnormally terminates the stream.
   *     <li>Both input and output streams have transmitted all data and headers.
   * </ul>
   *
   * <p>Note that the input stream may continue to yield data even after a stream reports itself as
   * not open. This is because input data is buffered.
   */
  public synchronized boolean isOpen() {
    if (errorCode != null) {
      return false;
    }
    if ((source.finished || source.closed)
        && (sink.finished || sink.closed)
        && hasResponseHeaders) {
      return false;
    }
    return true;
  }

  /** Returns true if this stream was created by this peer. */
  public boolean isLocallyInitiated() {
    boolean streamIsClient = ((id & 1) == 1);
    return connection.client == streamIsClient;
  }

  public Http2Connection getConnection() {
    return connection;
  }

  /**
   * Removes and returns the stream's received response headers, blocking if necessary until headers
   * have been received. If the returned list contains multiple blocks of headers the blocks will be
   * delimited by 'null'.
   */
  public synchronized Headers takeHeaders() throws IOException {
    readTimeout.enter();
    try {
      while (headersQueue.isEmpty() && errorCode == null) {
        waitForIo();
      }
    } finally {
      readTimeout.exitAndThrowIfTimedOut();
    }
    if (!headersQueue.isEmpty()) {
      return headersQueue.removeFirst();
    }
    throw new StreamResetException(errorCode);
  }

  /**
   * Returns the reason why this stream was closed, or null if it closed normally or has not yet
   * been closed.
   */
  public synchronized ErrorCode getErrorCode() {
    return errorCode;
  }

  /**
   * Sends a reply to an incoming stream.
   *
   * @param out true to create an output stream that we can use to send data to the remote peer.
   * Corresponds to {@code FLAG_FIN}.
   */
  public void writeHeaders(List<Header> responseHeaders, boolean out) throws IOException {
    assert (!Thread.holdsLock(Http2Stream.this));
    if (responseHeaders == null) {
      throw new NullPointerException("headers == null");
    }
    boolean outFinished = false;
    boolean flushHeaders = false;
    synchronized (this) {
      this.hasResponseHeaders = true;
      if (!out) {
        this.sink.finished = true;
        flushHeaders = true;
        outFinished = true;
      }
    }

    // Only DATA frames are subject to flow-control. Transmit the HEADER frame if the connection
    // flow-control window is fully depleted.
    if (!flushHeaders) {
      synchronized (connection) {
        flushHeaders = connection.bytesLeftInWriteWindow == 0L;
      }
    }

    // TODO(jwilson): rename to writeHeaders
    connection.writeSynReply(id, outFinished, responseHeaders);

    if (flushHeaders) {
      connection.flush();
    }
  }

  public Timeout readTimeout() {
    return readTimeout;
  }

  public Timeout writeTimeout() {
    return writeTimeout;
  }

  /** Returns a source that reads data from the peer. */
  public Source getSource() {
    return source;
  }

  /**
   * Returns a sink that can be used to write data to the peer.
   *
   * @throws IllegalStateException if this stream was initiated by the peer and a {@link
   * #writeHeaders} has not yet been sent.
   */
  public Sink getSink() {
    synchronized (this) {
      if (!hasResponseHeaders && !isLocallyInitiated()) {
        throw new IllegalStateException("reply before requesting the sink");
      }
    }
    return sink;
  }

  /**
   * Abnormally terminate this stream. This blocks until the {@code RST_STREAM} frame has been
   * transmitted.
   */
  public void close(ErrorCode rstStatusCode) throws IOException {
    if (!closeInternal(rstStatusCode)) {
      return; // Already closed.
    }
    connection.writeSynReset(id, rstStatusCode);
  }

  /**
   * Abnormally terminate this stream. This enqueues a {@code RST_STREAM} frame and returns
   * immediately.
   */
  public void closeLater(ErrorCode errorCode) {
    if (!closeInternal(errorCode)) {
      return; // Already closed.
    }
    connection.writeSynResetLater(id, errorCode);
  }

  /** Returns true if this stream was closed. */
  private boolean closeInternal(ErrorCode errorCode) {
    assert (!Thread.holdsLock(this));
    synchronized (this) {
      if (this.errorCode != null) {
        return false;
      }
      if (source.finished && sink.finished) {
        return false;
      }
      this.errorCode = errorCode;
      notifyAll();
    }
    connection.removeStream(id);
    return true;
  }

  /**
   * Accept headers from the network and store them until the client calls {@link #takeHeaders}, or
   * {@link FramingSource#read} them.
   */
  void receiveHeaders(List<Header> headers) {
    assert (!Thread.holdsLock(Http2Stream.this));
    boolean open;
    synchronized (this) {
      hasResponseHeaders = true;
      headersQueue.add(Util.toHeaders(headers));
      open = isOpen();
      notifyAll();
    }
    if (!open) {
      connection.removeStream(id);
    }
  }

  void receiveData(BufferedSource in, int length) throws IOException {
    assert (!Thread.holdsLock(Http2Stream.this));
    this.source.receive(in, length);
  }

  void receiveFin() {
    assert (!Thread.holdsLock(Http2Stream.this));
    boolean open;
    synchronized (this) {
      this.source.finished = true;
      open = isOpen();
      notifyAll();
    }
    if (!open) {
      connection.removeStream(id);
    }
  }

  synchronized void receiveRstStream(ErrorCode errorCode) {
    if (this.errorCode == null) {
      this.errorCode = errorCode;
      notifyAll();
    }
  }

  public synchronized void setHeadersListener(Header.Listener headersListener) {
    this.headersListener = headersListener;
  }

  /**
   * A source that reads the incoming data frames of a stream. Although this class uses
   * synchronization to safely receive incoming data frames, it is not intended for use by multiple
   * readers.
   */
  private final class FramingSource implements Source {
    /** Buffer to receive data from the network into. Only accessed by the reader thread. */
    private final Buffer receiveBuffer = new Buffer();

    /** Buffer with readable data. Guarded by Http2Stream.this. */
    private final Buffer readBuffer = new Buffer();

    /** Maximum number of bytes to buffer before reporting a flow control error. */
    private final long maxByteCount;

    /** True if the caller has closed this stream. */
    boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    boolean finished;

    FramingSource(long maxByteCount) {
      this.maxByteCount = maxByteCount;
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);

      while (true) {
        Headers headersToDeliver = null;
        Header.Listener headersListenerToNotify = null;
        long readBytesDelivered = -1;
        ErrorCode errorCodeToDeliver = null;

        // 1. Decide what to do in a synchronized block.

        synchronized (Http2Stream.this) {
          readTimeout.enter();
          try {
            if (errorCode != null) {
              // Prepare to deliver an error.
              errorCodeToDeliver = errorCode;
            }

            if (closed) {
              throw new IOException("stream closed");

            } else if (!headersQueue.isEmpty() && headersListener != null) {
              // Prepare to deliver headers.
              headersToDeliver = headersQueue.removeFirst();
              headersListenerToNotify = headersListener;

            } else if (readBuffer.size() > 0) {
              // Prepare to read bytes. Start by moving them to the caller's buffer.
              readBytesDelivered = readBuffer.read(sink, Math.min(byteCount, readBuffer.size()));
              unacknowledgedBytesRead += readBytesDelivered;

              if (errorCodeToDeliver == null
                  && unacknowledgedBytesRead
                  >= connection.okHttpSettings.getInitialWindowSize() / 2) {
                // Flow control: notify the peer that we're ready for more data! Only send a
                // WINDOW_UPDATE if the stream isn't in error.
                connection.writeWindowUpdateLater(id, unacknowledgedBytesRead);
                unacknowledgedBytesRead = 0;
              }
            } else if (!finished && errorCodeToDeliver == null) {
              // Nothing to do. Wait until that changes then try again.
              waitForIo();
              continue;
            }
          } finally {
            readTimeout.exitAndThrowIfTimedOut();
          }
        }

        // 2. Do it outside of the synchronized block and timeout.

        if (headersToDeliver != null && headersListenerToNotify != null) {
          headersListenerToNotify.onHeaders(headersToDeliver);
          continue;
        }

        if (readBytesDelivered != -1) {
          // Update connection.unacknowledgedBytesRead outside the synchronized block.
          updateConnectionFlowControl(readBytesDelivered);
          return readBytesDelivered;
        }

        if (errorCodeToDeliver != null) {
          // We defer throwing the exception until now so that we can refill the connection
          // flow-control window. This is necessary because we don't transmit window updates until
          // the application reads the data. If we throw this prior to updating the connection
          // flow-control window, we risk having it go to 0 preventing the server from sending data.
          throw new StreamResetException(errorCodeToDeliver);
        }

        return -1; // This source is exhausted.
      }
    }

    private void updateConnectionFlowControl(long read) {
      assert (!Thread.holdsLock(Http2Stream.this));
      connection.updateConnectionFlowControl(read);
    }

    void receive(BufferedSource in, long byteCount) throws IOException {
      assert (!Thread.holdsLock(Http2Stream.this));

      while (byteCount > 0) {
        boolean finished;
        boolean flowControlError;
        synchronized (Http2Stream.this) {
          finished = this.finished;
          flowControlError = byteCount + readBuffer.size() > maxByteCount;
        }

        // If the peer sends more data than we can handle, discard it and close the connection.
        if (flowControlError) {
          in.skip(byteCount);
          closeLater(ErrorCode.FLOW_CONTROL_ERROR);
          return;
        }

        // Discard data received after the stream is finished. It's probably a benign race.
        if (finished) {
          in.skip(byteCount);
          return;
        }

        // Fill the receive buffer without holding any locks.
        long read = in.read(receiveBuffer, byteCount);
        if (read == -1) throw new EOFException();
        byteCount -= read;

        // Move the received data to the read buffer to the reader can read it.
        synchronized (Http2Stream.this) {
          boolean wasEmpty = readBuffer.size() == 0;
          readBuffer.writeAll(receiveBuffer);
          if (wasEmpty) {
            Http2Stream.this.notifyAll();
          }
        }
      }
    }

    @Override public Timeout timeout() {
      return readTimeout;
    }

    @Override public void close() throws IOException {
      long bytesDiscarded;
      List<Headers> headersToDeliver = null;
      Header.Listener headersListenerToNotify = null;
      synchronized (Http2Stream.this) {
        closed = true;
        bytesDiscarded = readBuffer.size();
        readBuffer.clear();
        if (!headersQueue.isEmpty() && headersListener != null) {
          headersToDeliver = new ArrayList<>(headersQueue);
          headersQueue.clear();
          headersListenerToNotify = headersListener;
        }
        Http2Stream.this.notifyAll(); // TODO(jwilson): Unnecessary?
      }
      if (bytesDiscarded > 0) {
        updateConnectionFlowControl(bytesDiscarded);
      }
      cancelStreamIfNecessary();
      if (headersListenerToNotify != null) {
        for (Headers headers : headersToDeliver) {
          headersListenerToNotify.onHeaders(headers);
        }
      }
    }
  }

  void cancelStreamIfNecessary() throws IOException {
    assert (!Thread.holdsLock(Http2Stream.this));
    boolean open;
    boolean cancel;
    synchronized (this) {
      cancel = !source.finished && source.closed && (sink.finished || sink.closed);
      open = isOpen();
    }
    if (cancel) {
      // RST this stream to prevent additional data from being sent. This
      // is safe because the input stream is closed (we won't use any
      // further bytes) and the output stream is either finished or closed
      // (so RSTing both streams doesn't cause harm).
      Http2Stream.this.close(ErrorCode.CANCEL);
    } else if (!open) {
      connection.removeStream(id);
    }
  }

  /** A sink that writes outgoing data frames of a stream. This class is not thread safe. */
  final class FramingSink implements Sink {
    private static final long EMIT_BUFFER_SIZE = 16384;

    /**
     * Buffer of outgoing data. This batches writes of small writes into this sink as larges frames
     * written to the outgoing connection. Batching saves the (small) framing overhead.
     */
    private final Buffer sendBuffer = new Buffer();

    boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We shall send no more bytes.
     */
    boolean finished;

    @Override public void write(Buffer source, long byteCount) throws IOException {
      assert (!Thread.holdsLock(Http2Stream.this));
      sendBuffer.write(source, byteCount);
      while (sendBuffer.size() >= EMIT_BUFFER_SIZE) {
        emitFrame(false);
      }
    }

    /**
     * Emit a single data frame to the connection. The frame's size be limited by this stream's
     * write window. This method will block until the write window is nonempty.
     */
    private void emitFrame(boolean outFinished) throws IOException {
      long toWrite;
      synchronized (Http2Stream.this) {
        writeTimeout.enter();
        try {
          while (bytesLeftInWriteWindow <= 0 && !finished && !closed && errorCode == null) {
            waitForIo(); // Wait until we receive a WINDOW_UPDATE for this stream.
          }
        } finally {
          writeTimeout.exitAndThrowIfTimedOut();
        }

        checkOutNotClosed(); // Kick out if the stream was reset or closed while waiting.
        toWrite = Math.min(bytesLeftInWriteWindow, sendBuffer.size());
        bytesLeftInWriteWindow -= toWrite;
      }

      writeTimeout.enter();
      try {
        connection.writeData(id, outFinished && toWrite == sendBuffer.size(), sendBuffer, toWrite);
      } finally {
        writeTimeout.exitAndThrowIfTimedOut();
      }
    }

    @Override public void flush() throws IOException {
      assert (!Thread.holdsLock(Http2Stream.this));
      synchronized (Http2Stream.this) {
        checkOutNotClosed();
      }
      while (sendBuffer.size() > 0) {
        emitFrame(false);
        connection.flush();
      }
    }

    @Override public Timeout timeout() {
      return writeTimeout;
    }

    @Override public void close() throws IOException {
      assert (!Thread.holdsLock(Http2Stream.this));
      synchronized (Http2Stream.this) {
        if (closed) return;
      }
      if (!sink.finished) {
        // Emit the remaining data, setting the END_STREAM flag on the last frame.
        if (sendBuffer.size() > 0) {
          while (sendBuffer.size() > 0) {
            emitFrame(true);
          }
        } else {
          // Send an empty frame just so we can set the END_STREAM flag.
          connection.writeData(id, true, null, 0);
        }
      }
      synchronized (Http2Stream.this) {
        closed = true;
      }
      connection.flush();
      cancelStreamIfNecessary();
    }
  }

  /**
   * {@code delta} will be negative if a settings frame initial window is smaller than the last.
   */
  void addBytesToWriteWindow(long delta) {
    bytesLeftInWriteWindow += delta;
    if (delta > 0) Http2Stream.this.notifyAll();
  }

  void checkOutNotClosed() throws IOException {
    if (sink.closed) {
      throw new IOException("stream closed");
    } else if (sink.finished) {
      throw new IOException("stream finished");
    } else if (errorCode != null) {
      throw new StreamResetException(errorCode);
    }
  }

  /**
   * Like {@link #wait}, but throws an {@code InterruptedIOException} when interrupted instead of
   * the more awkward {@link InterruptedException}.
   */
  void waitForIo() throws InterruptedIOException {
    try {
      wait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Retain interrupted status.
      throw new InterruptedIOException();
    }
  }

  /**
   * The Okio timeout watchdog will call {@link #timedOut} if the timeout is reached. In that case
   * we close the stream (asynchronously) which will notify the waiting thread.
   */
  class StreamTimeout extends AsyncTimeout {
    @Override protected void timedOut() {
      closeLater(ErrorCode.CANCEL);
    }

    @Override protected IOException newTimeoutException(IOException cause) {
      SocketTimeoutException socketTimeoutException = new SocketTimeoutException("timeout");
      if (cause != null) {
        socketTimeoutException.initCause(cause);
      }
      return socketTimeoutException;
    }

    public void exitAndThrowIfTimedOut() throws IOException {
      if (exit()) throw newTimeoutException(null /* cause */);
    }
  }
}
