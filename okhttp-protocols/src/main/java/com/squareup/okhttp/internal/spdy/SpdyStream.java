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

package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.BufferedSource;
import com.squareup.okhttp.internal.bytes.Deadline;
import com.squareup.okhttp.internal.bytes.OkBuffer;
import com.squareup.okhttp.internal.bytes.Source;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

/** A logical bidirectional stream. */
public final class SpdyStream {
  static final int OUTPUT_BUFFER_SIZE = 8192;

  // Internal state is guarded by this. No long-running or potentially
  // blocking operations are performed while the lock is held.

  /**
   * The total number of bytes consumed by the application (with {@link
   * SpdyDataSource#read}), but not yet acknowledged by sending a {@code
   * WINDOW_UPDATE} frame on this stream.
   */
  // Visible for testing
  long unacknowledgedBytesRead = 0;

  /**
   * Count of bytes that can be written on the stream before receiving a
   * window update. Even if this is positive, writes will block until there
   * available bytes in {@code connection.bytesLeftInWriteWindow}.
   */
  // guarded by this
  long bytesLeftInWriteWindow;

  private final int id;
  private final SpdyConnection connection;
  private final int priority;
  private long readTimeoutMillis = 0;

  /** Headers sent by the stream initiator. Immutable and non null. */
  private final List<Header> requestHeaders;

  /** Headers sent in the stream reply. Null if reply is either not sent or not sent yet. */
  private List<Header> responseHeaders;

  private final SpdyDataSource source;
  final SpdyDataOutputStream out;

  /**
   * The reason why this stream was abnormally closed. If there are multiple
   * reasons to abnormally close this stream (such as both peers closing it
   * near-simultaneously) then this is the first reason known to this peer.
   */
  private ErrorCode errorCode = null;

  SpdyStream(int id, SpdyConnection connection, boolean outFinished, boolean inFinished,
      int priority, List<Header> requestHeaders) {
    if (connection == null) throw new NullPointerException("connection == null");
    if (requestHeaders == null) throw new NullPointerException("requestHeaders == null");
    this.id = id;
    this.connection = connection;
    this.bytesLeftInWriteWindow = connection.peerSettings.getInitialWindowSize();
    this.source = new SpdyDataSource(connection.okHttpSettings.getInitialWindowSize());
    this.out = new SpdyDataOutputStream();
    this.source.finished = inFinished;
    this.out.finished = outFinished;
    this.priority = priority;
    this.requestHeaders = requestHeaders;
  }

  /**
   * Returns true if this stream is open. A stream is open until either:
   * <ul>
   * <li>A {@code SYN_RESET} frame abnormally terminates the stream.
   * <li>Both input and output streams have transmitted all data and
   * headers.
   * </ul>
   * Note that the input stream may continue to yield data even after a stream
   * reports itself as not open. This is because input data is buffered.
   */
  public synchronized boolean isOpen() {
    if (errorCode != null) {
      return false;
    }
    if ((source.finished || source.closed)
        && (out.finished || out.closed)
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

  public SpdyConnection getConnection() {
    return connection;
  }

  public List<Header> getRequestHeaders() {
    return requestHeaders;
  }

  /**
   * Returns the stream's response headers, blocking if necessary if they
   * have not been received yet.
   */
  public synchronized List<Header> getResponseHeaders() throws IOException {
    long remaining = 0;
    long start = 0;
    if (readTimeoutMillis != 0) {
      start = (System.nanoTime() / 1000000);
      remaining = readTimeoutMillis;
    }
    try {
      while (responseHeaders == null && errorCode == null) {
        if (readTimeoutMillis == 0) { // No timeout configured.
          wait();
        } else if (remaining > 0) {
          wait(remaining);
          remaining = start + readTimeoutMillis - (System.nanoTime() / 1000000);
        } else {
          throw new SocketTimeoutException("Read response header timeout. readTimeoutMillis: "
                            + readTimeoutMillis);
        }
      }
      if (responseHeaders != null) {
        return responseHeaders;
      }
      throw new IOException("stream was reset: " + errorCode);
    } catch (InterruptedException e) {
      InterruptedIOException rethrow = new InterruptedIOException();
      rethrow.initCause(e);
      throw rethrow;
    }
  }

  /**
   * Returns the reason why this stream was closed, or null if it closed
   * normally or has not yet been closed.
   */
  public synchronized ErrorCode getErrorCode() {
    return errorCode;
  }

  /**
   * Sends a reply to an incoming stream.
   *
   * @param out true to create an output stream that we can use to send data
   * to the remote peer. Corresponds to {@code FLAG_FIN}.
   */
  public void reply(List<Header> responseHeaders, boolean out) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean outFinished = false;
    synchronized (this) {
      if (responseHeaders == null) {
        throw new NullPointerException("responseHeaders == null");
      }
      if (isLocallyInitiated()) {
        throw new IllegalStateException("cannot reply to a locally initiated stream");
      }
      if (this.responseHeaders != null) {
        throw new IllegalStateException("reply already sent");
      }
      this.responseHeaders = responseHeaders;
      if (!out) {
        this.out.finished = true;
        outFinished = true;
      }
    }
    connection.writeSynReply(id, outFinished, responseHeaders);
  }

  /**
   * Sets the maximum time to wait on input stream reads before failing with a
   * {@code SocketTimeoutException}, or {@code 0} to wait indefinitely.
   */
  public void setReadTimeout(long readTimeoutMillis) {
    this.readTimeoutMillis = readTimeoutMillis;
  }

  public long getReadTimeoutMillis() {
    return readTimeoutMillis;
  }

  /** Returns a source that reads data from the peer. */
  public Source getSource() {
    return source;
  }

  /**
   * Returns an output stream that can be used to write data to the peer.
   *
   * @throws IllegalStateException if this stream was initiated by the peer
   * and a {@link #reply} has not yet been sent.
   */
  public OutputStream getOutputStream() {
    synchronized (this) {
      if (responseHeaders == null && !isLocallyInitiated()) {
        throw new IllegalStateException("reply before requesting the output stream");
      }
    }
    return out;
  }

  /**
   * Abnormally terminate this stream. This blocks until the {@code RST_STREAM}
   * frame has been transmitted.
   */
  public void close(ErrorCode rstStatusCode) throws IOException {
    if (!closeInternal(rstStatusCode)) {
      return; // Already closed.
    }
    connection.writeSynReset(id, rstStatusCode);
  }

  /**
   * Abnormally terminate this stream. This enqueues a {@code RST_STREAM}
   * frame and returns immediately.
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
      if (source.finished && out.finished) {
        return false;
      }
      this.errorCode = errorCode;
      notifyAll();
    }
    connection.removeStream(id);
    return true;
  }

  void receiveHeaders(List<Header> headers, HeadersMode headersMode) {
    assert (!Thread.holdsLock(SpdyStream.this));
    ErrorCode errorCode = null;
    boolean open = true;
    synchronized (this) {
      if (responseHeaders == null) {
        if (headersMode.failIfHeadersAbsent()) {
          errorCode = ErrorCode.PROTOCOL_ERROR;
        } else {
          responseHeaders = headers;
          open = isOpen();
          notifyAll();
        }
      } else {
        if (headersMode.failIfHeadersPresent()) {
          errorCode = ErrorCode.STREAM_IN_USE;
        } else {
          List<Header> newHeaders = new ArrayList<Header>();
          newHeaders.addAll(responseHeaders);
          newHeaders.addAll(headers);
          this.responseHeaders = newHeaders;
        }
      }
    }
    if (errorCode != null) {
      closeLater(errorCode);
    } else if (!open) {
      connection.removeStream(id);
    }
  }

  void receiveData(BufferedSource in, int length) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    this.source.receive(in, length);
  }

  void receiveFin() {
    assert (!Thread.holdsLock(SpdyStream.this));
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

  int getPriority() {
    return priority;
  }

  /**
   * A source that reads the incoming data frames of a stream. Although this
   * class uses synchronization to safely receive incoming data frames, it is
   * not intended for use by multiple readers.
   */
  private final class SpdyDataSource implements Source {
    /** Buffer to receive data from the network into. Only accessed by the reader thread. */
    private final OkBuffer receiveBuffer = new OkBuffer();

    /** Buffer with readable data. Guarded by SpdyStream.this. */
    private final OkBuffer readBuffer = new OkBuffer();

    /** Maximum number of bytes to buffer before reporting a flow control error. */
    private final long maxByteCount;

    /** True if the caller has closed this stream. */
    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We will
     * receive no more bytes beyond those already in the buffer.
     */
    private boolean finished;

    private SpdyDataSource(long maxByteCount) {
      this.maxByteCount = maxByteCount;
    }

    @Override public long read(OkBuffer sink, long byteCount, Deadline deadline)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);

      long read;
      synchronized (SpdyStream.this) {
        waitUntilReadable();
        checkNotClosed();
        if (readBuffer.byteCount() == 0) return -1; // This source is exhausted.

        // Move bytes from the read buffer into the caller's buffer.
        read = readBuffer.read(sink, Math.min(byteCount, readBuffer.byteCount()), deadline);

        // Flow control: notify the peer that we're ready for more data!
        unacknowledgedBytesRead += read;
        if (unacknowledgedBytesRead >= connection.okHttpSettings.getInitialWindowSize() / 2) {
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

    /**
     * Returns once the input stream is either readable or finished. Throws
     * a {@link SocketTimeoutException} if the read timeout elapses before
     * that happens.
     */
    private void waitUntilReadable() throws IOException {
      long start = 0;
      long remaining = 0;
      if (readTimeoutMillis != 0) {
        start = (System.nanoTime() / 1000000);
        remaining = readTimeoutMillis;
      }
      try {
        while (readBuffer.byteCount() == 0 && !finished && !closed && errorCode == null) {
          if (readTimeoutMillis == 0) {
            SpdyStream.this.wait();
          } else if (remaining > 0) {
            SpdyStream.this.wait(remaining);
            remaining = start + readTimeoutMillis - (System.nanoTime() / 1000000);
          } else {
            throw new SocketTimeoutException();
          }
        }
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      }
    }

    void receive(BufferedSource in, long byteCount) throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));

      while (byteCount > 0) {
        boolean finished;
        boolean flowControlError;
        synchronized (SpdyStream.this) {
          finished = this.finished;
          flowControlError = byteCount + readBuffer.byteCount() > maxByteCount;
        }

        // If the peer sends more data than we can handle, discard it and close the connection.
        if (flowControlError) {
          in.skip(byteCount, Deadline.NONE);
          closeLater(ErrorCode.FLOW_CONTROL_ERROR);
          return;
        }

        // Discard data received after the stream is finished. It's probably a benign race.
        if (finished) {
          in.skip(byteCount, Deadline.NONE);
          return;
        }

        // Fill the receive buffer without holding any locks.
        long read = in.read(receiveBuffer, byteCount, Deadline.NONE);
        if (read == -1) throw new EOFException();
        byteCount -= read;

        // Move the received data to the read buffer to the reader can read it.
        synchronized (SpdyStream.this) {
          boolean wasEmpty = readBuffer.byteCount() == 0;
          readBuffer.write(receiveBuffer, receiveBuffer.byteCount(), Deadline.NONE);
          if (wasEmpty) {
            SpdyStream.this.notifyAll();
          }
        }
      }
    }

    @Override public void close(Deadline deadline) throws IOException {
      synchronized (SpdyStream.this) {
        closed = true;
        readBuffer.clear();
        SpdyStream.this.notifyAll();
      }
      cancelStreamIfNecessary();
    }

    private void checkNotClosed() throws IOException {
      if (closed) {
        throw new IOException("stream closed");
      }
      if (errorCode != null) {
        throw new IOException("stream was reset: " + errorCode);
      }
    }
  }

  private void cancelStreamIfNecessary() throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean open;
    boolean cancel;
    synchronized (this) {
      cancel = !source.finished && source.closed && (out.finished || out.closed);
      open = isOpen();
    }
    if (cancel) {
      // RST this stream to prevent additional data from being sent. This
      // is safe because the input stream is closed (we won't use any
      // further bytes) and the output stream is either finished or closed
      // (so RSTing both streams doesn't cause harm).
      SpdyStream.this.close(ErrorCode.CANCEL);
    } else if (!open) {
      connection.removeStream(id);
    }
  }

  /**
   * An output stream that writes outgoing data frames of a stream. This class
   * is not thread safe.
   */
  final class SpdyDataOutputStream extends OutputStream {
    private final byte[] buffer = SpdyStream.this.connection.bufferPool.getBuf(OUTPUT_BUFFER_SIZE);
    private int pos = 0;

    /** True if the caller has closed this stream. */
    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We shall send
     * no more bytes.
     */
    private boolean finished;

    @Override public void write(int b) throws IOException {
      Util.writeSingleByte(this, b);
    }

    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));
      checkOffsetAndCount(bytes.length, offset, count);
      synchronized (SpdyStream.this) {
        checkOutNotClosed();
      }

      while (count > 0) {
        if (pos == buffer.length) {
          writeFrame();
        }
        int bytesToCopy = Math.min(count, buffer.length - pos);
        System.arraycopy(bytes, offset, buffer, pos, bytesToCopy);
        pos += bytesToCopy;
        offset += bytesToCopy;
        count -= bytesToCopy;
      }
    }

    @Override public void flush() throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));
      synchronized (SpdyStream.this) {
        checkOutNotClosed();
      }
      if (pos > 0) {
        writeFrame();
        connection.flush();
      }
    }

    @Override public void close() throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));
      synchronized (SpdyStream.this) {
        if (closed) {
          return;
        }
        closed = true;
        SpdyStream.this.connection.bufferPool.returnBuf(buffer);
      }
      if (!out.finished) {
        connection.writeData(id, true, buffer, 0, pos);
      }
      connection.flush();
      cancelStreamIfNecessary();
    }

    private void writeFrame() throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));

      int length = pos;
      synchronized (SpdyStream.this) {
        waitUntilWritable(length);
        checkOutNotClosed(); // Kick out if the stream was reset or closed while waiting.
        bytesLeftInWriteWindow -= length;
      }
      connection.writeData(id, false, buffer, 0, pos);
      pos = 0;
    }
  }

  /** Returns once the peer is ready to receive {@code byteCount} bytes. */
  private void waitUntilWritable(int byteCount) throws IOException {
    try {
      while (byteCount > bytesLeftInWriteWindow) {
        SpdyStream.this.wait(); // Wait until we receive a WINDOW_UPDATE.
      }
    } catch (InterruptedException e) {
      throw new InterruptedIOException();
    }
  }

  /**
   * {@code delta} will be negative if a settings frame initial window is
   * smaller than the last.
   */
  void addBytesToWriteWindow(long delta) {
    bytesLeftInWriteWindow += delta;
    if (delta > 0) SpdyStream.this.notifyAll();
  }

  private void checkOutNotClosed() throws IOException {
    if (out.closed) {
      throw new IOException("stream closed");
    } else if (out.finished) {
      throw new IOException("stream finished");
    } else if (errorCode != null) {
      throw new IOException("stream was reset: " + errorCode);
    }
  }
}
