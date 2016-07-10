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
import java.util.ArrayList;
import java.util.List;
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
   * The total number of bytes consumed by the application (with {@link FramedDataSource#read}), but
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

  private final int id;
  private final Http2Connection connection;

  /** Headers sent by the stream initiator. Immutable and non null. */
  private final List<Header> requestHeaders;

  /** Headers sent in the stream reply. Null if reply is either not sent or not sent yet. */
  private List<Header> responseHeaders;

  private final FramedDataSource source;
  final FramedDataSink sink;
  private final StreamTimeout readTimeout = new StreamTimeout();
  private final StreamTimeout writeTimeout = new StreamTimeout();

  /**
   * The reason why this stream was abnormally closed. If there are multiple reasons to abnormally
   * close this stream (such as both peers closing it near-simultaneously) then this is the first
   * reason known to this peer.
   */
  private ErrorCode errorCode = null;

  Http2Stream(int id, Http2Connection connection, boolean outFinished, boolean inFinished,
      List<Header> requestHeaders) {
    if (connection == null) throw new NullPointerException("connection == null");
    if (requestHeaders == null) throw new NullPointerException("requestHeaders == null");
    this.id = id;
    this.connection = connection;
    this.bytesLeftInWriteWindow =
        connection.peerSettings.getInitialWindowSize();
    this.source = new FramedDataSource(
        connection.okHttpSettings.getInitialWindowSize());
    this.sink = new FramedDataSink();
    this.source.finished = inFinished;
    this.sink.finished = outFinished;
    this.requestHeaders = requestHeaders;
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
        && responseHeaders != null) {
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

  public List<Header> getRequestHeaders() {
    return requestHeaders;
  }

  /**
   * Returns the stream's response headers, blocking if necessary if they have not been received
   * yet.
   */
  public synchronized List<Header> getResponseHeaders() throws IOException {
    readTimeout.enter();
    try {
      while (responseHeaders == null && errorCode == null) {
        waitForIo();
      }
    } finally {
      readTimeout.exitAndThrowIfTimedOut();
    }
    if (responseHeaders != null) return responseHeaders;
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
  public void reply(List<Header> responseHeaders, boolean out) throws IOException {
    assert (!Thread.holdsLock(Http2Stream.this));
    boolean outFinished = false;
    synchronized (this) {
      if (responseHeaders == null) {
        throw new NullPointerException("responseHeaders == null");
      }
      if (this.responseHeaders != null) {
        throw new IllegalStateException("reply already sent");
      }
      this.responseHeaders = responseHeaders;
      if (!out) {
        this.sink.finished = true;
        outFinished = true;
      }
    }
    connection.writeSynReply(id, outFinished, responseHeaders);

    if (outFinished) {
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
   * @throws IllegalStateException if this stream was initiated by the peer and a {@link #reply} has
   * not yet been sent.
   */
  public Sink getSink() {
    synchronized (this) {
      if (responseHeaders == null && !isLocallyInitiated()) {
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

  void receiveHeaders(List<Header> headers) {
    assert (!Thread.holdsLock(Http2Stream.this));
    boolean open = true;
    synchronized (this) {
      if (responseHeaders == null) {
        responseHeaders = headers;
        open = isOpen();
        notifyAll();
      } else {
        List<Header> newHeaders = new ArrayList<>();
        newHeaders.addAll(responseHeaders);
        newHeaders.addAll(headers);
        this.responseHeaders = newHeaders;
      }
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

  /**
   * A source that reads the incoming data frames of a stream. Although this class uses
   * synchronization to safely receive incoming data frames, it is not intended for use by multiple
   * readers.
   */
  private final class FramedDataSource implements Source {
    /** Buffer to receive data from the network into. Only accessed by the reader thread. */
    private final Buffer receiveBuffer = new Buffer();

    /** Buffer with readable data. Guarded by FramedStream.this. */
    private final Buffer readBuffer = new Buffer();

    /** Maximum number of bytes to buffer before reporting a flow control error. */
    private final long maxByteCount;

    /** True if the caller has closed this stream. */
    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    private boolean finished;

    private FramedDataSource(long maxByteCount) {
      this.maxByteCount = maxByteCount;
    }

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);

      long read;
      synchronized (Http2Stream.this) {
        waitUntilReadable();
        checkNotClosed();
        if (readBuffer.size() == 0) return -1; // This source is exhausted.

        // Move bytes from the read buffer into the caller's buffer.
        read = readBuffer.read(sink, Math.min(byteCount, readBuffer.size()));

        // Flow control: notify the peer that we're ready for more data!
        unacknowledgedBytesRead += read;
        if (unacknowledgedBytesRead
            >= connection.okHttpSettings.getInitialWindowSize() / 2) {
          connection.writeWindowUpdateLater(id, unacknowledgedBytesRead);
          unacknowledgedBytesRead = 0;
        }
      }

      // Update connection.unacknowledgedBytesRead outside the stream lock.
      synchronized (connection) { // Multiple application threads may hit this section.
        connection.unacknowledgedBytesRead += read;
        if (connection.unacknowledgedBytesRead
            >= connection.okHttpSettings.getInitialWindowSize() / 2) {
          connection.writeWindowUpdateLater(0, connection.unacknowledgedBytesRead);
          connection.unacknowledgedBytesRead = 0;
        }
      }

      return read;
    }

    /** Returns once the source is either readable or finished. */
    private void waitUntilReadable() throws IOException {
      readTimeout.enter();
      try {
        while (readBuffer.size() == 0 && !finished && !closed && errorCode == null) {
          waitForIo();
        }
      } finally {
        readTimeout.exitAndThrowIfTimedOut();
      }
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
      synchronized (Http2Stream.this) {
        closed = true;
        readBuffer.clear();
        Http2Stream.this.notifyAll();
      }
      cancelStreamIfNecessary();
    }

    private void checkNotClosed() throws IOException {
      if (closed) {
        throw new IOException("stream closed");
      }
      if (errorCode != null) {
        throw new StreamResetException(errorCode);
      }
    }
  }

  private void cancelStreamIfNecessary() throws IOException {
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

  /**
   * A sink that writes outgoing data frames of a stream. This class is not thread safe.
   */
  final class FramedDataSink implements Sink {
    private static final long EMIT_BUFFER_SIZE = 16384;

    /**
     * Buffer of outgoing data. This batches writes of small writes into this sink as larges frames
     * written to the outgoing connection. Batching saves the (small) framing overhead.
     */
    private final Buffer sendBuffer = new Buffer();

    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We shall send no more bytes.
     */
    private boolean finished;

    @Override public void write(Buffer source, long byteCount) throws IOException {
      assert (!Thread.holdsLock(Http2Stream.this));
      sendBuffer.write(source, byteCount);
      while (sendBuffer.size() >= EMIT_BUFFER_SIZE) {
        emitDataFrame(false);
      }
    }

    /**
     * Emit a single data frame to the connection. The frame's size be limited by this stream's
     * write window. This method will block until the write window is nonempty.
     */
    private void emitDataFrame(boolean outFinished) throws IOException {
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
        emitDataFrame(false);
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
            emitDataFrame(true);
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

  private void checkOutNotClosed() throws IOException {
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
  private void waitForIo() throws InterruptedIOException {
    try {
      wait();
    } catch (InterruptedException e) {
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
