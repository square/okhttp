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
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;
import static com.squareup.okhttp.internal.Util.pokeInt;
import static java.nio.ByteOrder.BIG_ENDIAN;

/** A logical bidirectional stream. */
public final class SpdyStream {

  // Internal state is guarded by this. No long-running or potentially
  // blocking operations are performed while the lock is held.

  private static final int DATA_FRAME_HEADER_LENGTH = 8;

  private static final String[] STATUS_CODE_NAMES = {
      null,
      "PROTOCOL_ERROR",
      "INVALID_STREAM",
      "REFUSED_STREAM",
      "UNSUPPORTED_VERSION",
      "CANCEL",
      "INTERNAL_ERROR",
      "FLOW_CONTROL_ERROR",
      "STREAM_IN_USE",
      "STREAM_ALREADY_CLOSED",
      "INVALID_CREDENTIALS",
      "FRAME_TOO_LARGE"
  };

  public static final int RST_PROTOCOL_ERROR = 1;
  public static final int RST_INVALID_STREAM = 2;
  public static final int RST_REFUSED_STREAM = 3;
  public static final int RST_UNSUPPORTED_VERSION = 4;
  public static final int RST_CANCEL = 5;
  public static final int RST_INTERNAL_ERROR = 6;
  public static final int RST_FLOW_CONTROL_ERROR = 7;
  public static final int RST_STREAM_IN_USE = 8;
  public static final int RST_STREAM_ALREADY_CLOSED = 9;
  public static final int RST_INVALID_CREDENTIALS = 10;
  public static final int RST_FRAME_TOO_LARGE = 11;

  /**
   * The number of unacknowledged bytes at which the input stream will send
   * the peer a {@code WINDOW_UPDATE} frame. Must be less than this client's
   * window size, otherwise the remote peer will stop sending data on this
   * stream. (Chrome 25 uses 5 MiB.)
   */
  public static final int WINDOW_UPDATE_THRESHOLD = Settings.DEFAULT_INITIAL_WINDOW_SIZE / 2;

  private final int id;
  private final SpdyConnection connection;
  private final int priority;
  private final int slot;
  private long readTimeoutMillis = 0;
  private int writeWindowSize;

  /** Headers sent by the stream initiator. Immutable and non null. */
  private final List<String> requestHeaders;

  /** Headers sent in the stream reply. Null if reply is either not sent or not sent yet. */
  private List<String> responseHeaders;

  private final SpdyDataInputStream in = new SpdyDataInputStream();
  private final SpdyDataOutputStream out = new SpdyDataOutputStream();

  /**
   * The reason why this stream was abnormally closed. If there are multiple
   * reasons to abnormally close this stream (such as both peers closing it
   * near-simultaneously) then this is the first reason known to this peer.
   */
  private int rstStatusCode = -1;

  SpdyStream(int id, SpdyConnection connection, int flags, int priority, int slot,
      List<String> requestHeaders, Settings settings) {
    if (connection == null) throw new NullPointerException("connection == null");
    if (requestHeaders == null) throw new NullPointerException("requestHeaders == null");
    this.id = id;
    this.connection = connection;
    this.priority = priority;
    this.slot = slot;
    this.requestHeaders = requestHeaders;

    if (isLocallyInitiated()) {
      // I am the sender
      in.finished = (flags & SpdyConnection.FLAG_UNIDIRECTIONAL) != 0;
      out.finished = (flags & SpdyConnection.FLAG_FIN) != 0;
    } else {
      // I am the receiver
      in.finished = (flags & SpdyConnection.FLAG_FIN) != 0;
      out.finished = (flags & SpdyConnection.FLAG_UNIDIRECTIONAL) != 0;
    }

    setSettings(settings);
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
    if (rstStatusCode != -1) {
      return false;
    }
    if ((in.finished || in.closed) && (out.finished || out.closed) && responseHeaders != null) {
      return false;
    }
    return true;
  }

  /** Returns true if this stream was created by this peer. */
  public boolean isLocallyInitiated() {
    boolean streamIsClient = (id % 2 == 1);
    return connection.client == streamIsClient;
  }

  public SpdyConnection getConnection() {
    return connection;
  }

  public List<String> getRequestHeaders() {
    return requestHeaders;
  }

  /**
   * Returns the stream's response headers, blocking if necessary if they
   * have not been received yet.
   */
  public synchronized List<String> getResponseHeaders() throws IOException {
    try {
      while (responseHeaders == null && rstStatusCode == -1) {
        wait();
      }
      if (responseHeaders != null) {
        return responseHeaders;
      }
      throw new IOException("stream was reset: " + rstStatusString());
    } catch (InterruptedException e) {
      InterruptedIOException rethrow = new InterruptedIOException();
      rethrow.initCause(e);
      throw rethrow;
    }
  }

  /**
   * Returns the reason why this stream was closed, or -1 if it closed
   * normally or has not yet been closed. Valid reasons are {@link
   * #RST_PROTOCOL_ERROR}, {@link #RST_INVALID_STREAM}, {@link
   * #RST_REFUSED_STREAM}, {@link #RST_UNSUPPORTED_VERSION}, {@link
   * #RST_CANCEL}, {@link #RST_INTERNAL_ERROR} and {@link
   * #RST_FLOW_CONTROL_ERROR}.
   */
  public synchronized int getRstStatusCode() {
    return rstStatusCode;
  }

  /**
   * Sends a reply to an incoming stream.
   *
   * @param out true to create an output stream that we can use to send data
   * to the remote peer. Corresponds to {@code FLAG_FIN}.
   */
  public void reply(List<String> responseHeaders, boolean out) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    int flags = 0;
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
        flags |= SpdyConnection.FLAG_FIN;
      }
    }
    connection.writeSynReply(id, flags, responseHeaders);
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

  /** Returns an input stream that can be used to read data from the peer. */
  public InputStream getInputStream() {
    return in;
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
  public void close(int rstStatusCode) throws IOException {
    if (!closeInternal(rstStatusCode)) {
      return; // Already closed.
    }
    connection.writeSynReset(id, rstStatusCode);
  }

  /**
   * Abnormally terminate this stream. This enqueues a {@code RST_STREAM}
   * frame and returns immediately.
   */
  public void closeLater(int rstStatusCode) {
    if (!closeInternal(rstStatusCode)) {
      return; // Already closed.
    }
    connection.writeSynResetLater(id, rstStatusCode);
  }

  /** Returns true if this stream was closed. */
  private boolean closeInternal(int rstStatusCode) {
    assert (!Thread.holdsLock(this));
    synchronized (this) {
      if (this.rstStatusCode != -1) {
        return false;
      }
      if (in.finished && out.finished) {
        return false;
      }
      this.rstStatusCode = rstStatusCode;
      notifyAll();
    }
    connection.removeStream(id);
    return true;
  }

  void receiveReply(List<String> strings) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean streamInUseError = false;
    boolean open = true;
    synchronized (this) {
      if (isLocallyInitiated() && responseHeaders == null) {
        responseHeaders = strings;
        open = isOpen();
        notifyAll();
      } else {
        streamInUseError = true;
      }
    }
    if (streamInUseError) {
      closeLater(SpdyStream.RST_STREAM_IN_USE);
    } else if (!open) {
      connection.removeStream(id);
    }
  }

  void receiveHeaders(List<String> headers) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean protocolError = false;
    synchronized (this) {
      if (responseHeaders != null) {
        List<String> newHeaders = new ArrayList<String>();
        newHeaders.addAll(responseHeaders);
        newHeaders.addAll(headers);
        this.responseHeaders = newHeaders;
      } else {
        protocolError = true;
      }
    }
    if (protocolError) {
      closeLater(SpdyStream.RST_PROTOCOL_ERROR);
    }
  }

  void receiveData(InputStream in, int length) throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    this.in.receive(in, length);
  }

  void receiveFin() {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean open;
    synchronized (this) {
      this.in.finished = true;
      open = isOpen();
      notifyAll();
    }
    if (!open) {
      connection.removeStream(id);
    }
  }

  synchronized void receiveRstStream(int statusCode) {
    if (rstStatusCode == -1) {
      rstStatusCode = statusCode;
      notifyAll();
    }
  }

  private void setSettings(Settings settings) {
    assert (Thread.holdsLock(connection)); // Because 'settings' is guarded by 'connection'.
    this.writeWindowSize =
        settings != null ? settings.getInitialWindowSize(Settings.DEFAULT_INITIAL_WINDOW_SIZE)
            : Settings.DEFAULT_INITIAL_WINDOW_SIZE;
  }

  void receiveSettings(Settings settings) {
    assert (Thread.holdsLock(this));
    setSettings(settings);
    notifyAll();
  }

  synchronized void receiveWindowUpdate(int deltaWindowSize) {
    out.unacknowledgedBytes -= deltaWindowSize;
    notifyAll();
  }

  private String rstStatusString() {
    return rstStatusCode > 0 && rstStatusCode < STATUS_CODE_NAMES.length
        ? STATUS_CODE_NAMES[rstStatusCode] : Integer.toString(rstStatusCode);
  }

  int getPriority() {
    return priority;
  }

  int getSlot() {
    return slot;
  }

  /**
   * An input stream that reads the incoming data frames of a stream. Although
   * this class uses synchronization to safely receive incoming data frames,
   * it is not intended for use by multiple readers.
   */
  private final class SpdyDataInputStream extends InputStream {
    // Store incoming data bytes in a circular buffer. When the buffer is
    // empty, pos == -1. Otherwise pos is the first byte to read and limit
    // is the first byte to write.
    //
    // { - - - X X X X - - - }
    //         ^       ^
    //        pos    limit
    //
    // { X X X - - - - X X X }
    //         ^       ^
    //       limit    pos

    private final byte[] buffer = new byte[Settings.DEFAULT_INITIAL_WINDOW_SIZE];

    /** the next byte to be read, or -1 if the buffer is empty. Never buffer.length */
    private int pos = -1;

    /** the last byte to be read. Never buffer.length */
    private int limit;

    /** True if the caller has closed this stream. */
    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We will
     * receive no more bytes beyond those already in the buffer.
     */
    private boolean finished;

    /**
     * The total number of bytes consumed by the application (with {@link
     * #read}), but not yet acknowledged by sending a {@code WINDOW_UPDATE}
     * frame.
     */
    private int unacknowledgedBytes = 0;

    @Override public int available() throws IOException {
      synchronized (SpdyStream.this) {
        checkNotClosed();
        if (pos == -1) {
          return 0;
        } else if (limit > pos) {
          return limit - pos;
        } else {
          return limit + (buffer.length - pos);
        }
      }
    }

    @Override public int read() throws IOException {
      return Util.readSingleByte(this);
    }

    @Override public int read(byte[] b, int offset, int count) throws IOException {
      synchronized (SpdyStream.this) {
        checkOffsetAndCount(b.length, offset, count);
        waitUntilReadable();
        checkNotClosed();

        if (pos == -1) {
          return -1;
        }

        int copied = 0;

        // drain from [pos..buffer.length)
        if (limit <= pos) {
          int bytesToCopy = Math.min(count, buffer.length - pos);
          System.arraycopy(buffer, pos, b, offset, bytesToCopy);
          pos += bytesToCopy;
          copied += bytesToCopy;
          if (pos == buffer.length) {
            pos = 0;
          }
        }

        // drain from [pos..limit)
        if (copied < count) {
          int bytesToCopy = Math.min(limit - pos, count - copied);
          System.arraycopy(buffer, pos, b, offset + copied, bytesToCopy);
          pos += bytesToCopy;
          copied += bytesToCopy;
        }

        // Flow control: notify the peer that we're ready for more data!
        unacknowledgedBytes += copied;
        if (unacknowledgedBytes >= WINDOW_UPDATE_THRESHOLD) {
          connection.writeWindowUpdateLater(id, unacknowledgedBytes);
          unacknowledgedBytes = 0;
        }

        if (pos == limit) {
          pos = -1;
          limit = 0;
        }

        return copied;
      }
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
        while (pos == -1 && !finished && !closed && rstStatusCode == -1) {
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

    void receive(InputStream in, int byteCount) throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));

      if (byteCount == 0) {
        return;
      }

      int pos;
      int limit;
      int firstNewByte;
      boolean finished;
      boolean flowControlError;
      synchronized (SpdyStream.this) {
        finished = this.finished;
        pos = this.pos;
        firstNewByte = this.limit;
        limit = this.limit;
        flowControlError = byteCount > buffer.length - available();
      }

      // If the peer sends more data than we can handle, discard it and close the connection.
      if (flowControlError) {
        Util.skipByReading(in, byteCount);
        closeLater(SpdyStream.RST_FLOW_CONTROL_ERROR);
        return;
      }

      // Discard data received after the stream is finished. It's probably a benign race.
      if (finished) {
        Util.skipByReading(in, byteCount);
        return;
      }

      // Fill the buffer without holding any locks. First fill [limit..buffer.length) if that
      // won't overwrite unread data. Then fill [limit..pos). We can't hold a lock, otherwise
      // writes will be blocked until reads complete.
      if (pos < limit) {
        int firstCopyCount = Math.min(byteCount, buffer.length - limit);
        Util.readFully(in, buffer, limit, firstCopyCount);
        limit += firstCopyCount;
        byteCount -= firstCopyCount;
        if (limit == buffer.length) {
          limit = 0;
        }
      }
      if (byteCount > 0) {
        Util.readFully(in, buffer, limit, byteCount);
        limit += byteCount;
      }

      synchronized (SpdyStream.this) {
        // Update the new limit, and mark the position as readable if necessary.
        this.limit = limit;
        if (this.pos == -1) {
          this.pos = firstNewByte;
          SpdyStream.this.notifyAll();
        }
      }
    }

    @Override public void close() throws IOException {
      synchronized (SpdyStream.this) {
        closed = true;
        SpdyStream.this.notifyAll();
      }
      cancelStreamIfNecessary();
    }

    private void checkNotClosed() throws IOException {
      if (closed) {
        throw new IOException("stream closed");
      }
      if (rstStatusCode != -1) {
        throw new IOException("stream was reset: " + rstStatusString());
      }
    }
  }

  private void cancelStreamIfNecessary() throws IOException {
    assert (!Thread.holdsLock(SpdyStream.this));
    boolean open;
    boolean cancel;
    synchronized (this) {
      cancel = !in.finished && in.closed && (out.finished || out.closed);
      open = isOpen();
    }
    if (cancel) {
      // RST this stream to prevent additional data from being sent. This
      // is safe because the input stream is closed (we won't use any
      // further bytes) and the output stream is either finished or closed
      // (so RSTing both streams doesn't cause harm).
      SpdyStream.this.close(RST_CANCEL);
    } else if (!open) {
      connection.removeStream(id);
    }
  }

  /**
   * An output stream that writes outgoing data frames of a stream. This class
   * is not thread safe.
   */
  private final class SpdyDataOutputStream extends OutputStream {
    private final byte[] buffer = new byte[8192];
    private int pos = DATA_FRAME_HEADER_LENGTH;

    /** True if the caller has closed this stream. */
    private boolean closed;

    /**
     * True if either side has cleanly shut down this stream. We shall send
     * no more bytes.
     */
    private boolean finished;

    /**
     * The total number of bytes written out to the peer, but not yet
     * acknowledged with an incoming {@code WINDOW_UPDATE} frame. Writes
     * block if they cause this to exceed the {@code WINDOW_SIZE}.
     */
    private int unacknowledgedBytes = 0;

    @Override public void write(int b) throws IOException {
      Util.writeSingleByte(this, b);
    }

    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));
      checkOffsetAndCount(bytes.length, offset, count);
      checkNotClosed();

      while (count > 0) {
        if (pos == buffer.length) {
          writeFrame(false);
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
      checkNotClosed();
      if (pos > DATA_FRAME_HEADER_LENGTH) {
        writeFrame(false);
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
      }
      if (!out.finished) {
        writeFrame(true);
      }
      connection.flush();
      cancelStreamIfNecessary();
    }

    private void writeFrame(boolean last) throws IOException {
      assert (!Thread.holdsLock(SpdyStream.this));

      int length = pos - DATA_FRAME_HEADER_LENGTH;
      synchronized (SpdyStream.this) {
        waitUntilWritable(length, last);
        unacknowledgedBytes += length;
      }
      int flags = 0;
      if (last) {
        flags |= SpdyConnection.FLAG_FIN;
      }
      pokeInt(buffer, 0, id & 0x7fffffff, BIG_ENDIAN);
      pokeInt(buffer, 4, (flags & 0xff) << 24 | length & 0xffffff, BIG_ENDIAN);
      connection.writeFrame(buffer, 0, pos);
      pos = DATA_FRAME_HEADER_LENGTH;
    }

    /**
     * Returns once the peer is ready to receive {@code count} bytes.
     *
     * @throws IOException if the stream was finished or closed, or the
     * thread was interrupted.
     */
    private void waitUntilWritable(int count, boolean last) throws IOException {
      try {
        while (unacknowledgedBytes + count >= writeWindowSize) {
          SpdyStream.this.wait(); // Wait until we receive a WINDOW_UPDATE.

          // The stream may have been closed or reset while we were waiting!
          if (!last && closed) {
            throw new IOException("stream closed");
          } else if (finished) {
            throw new IOException("stream finished");
          } else if (rstStatusCode != -1) {
            throw new IOException("stream was reset: " + rstStatusString());
          }
        }
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      }
    }

    private void checkNotClosed() throws IOException {
      synchronized (SpdyStream.this) {
        if (closed) {
          throw new IOException("stream closed");
        } else if (finished) {
          throw new IOException("stream finished");
        } else if (rstStatusCode != -1) {
          throw new IOException("stream was reset: " + rstStatusString());
        }
      }
    }
  }
}
