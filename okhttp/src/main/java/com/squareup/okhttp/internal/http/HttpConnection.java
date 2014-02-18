/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.AbstractOutputStream;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.BufferedSource;
import com.squareup.okhttp.internal.bytes.Deadline;
import com.squareup.okhttp.internal.bytes.OkBuffer;
import com.squareup.okhttp.internal.bytes.OkBuffers;
import com.squareup.okhttp.internal.bytes.Source;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.ProtocolException;
import java.net.Socket;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_CONTINUE;
import static com.squareup.okhttp.internal.http.Transport.DISCARD_STREAM_TIMEOUT_MILLIS;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class
 * strictly enforces the following lifecycle:
 * <ol>
 *   <li>{@link #writeRequest Send request headers}.
 *   <li>Open the request body output stream. Either {@link
 *       #newFixedLengthOutputStream fixed-length} or {@link
 *       #newChunkedOutputStream chunked}.
 *   <li>Write to and then close that stream.
 *   <li>{@link #readResponse Read response headers}.
 *   <li>Open the HTTP response body input stream. Either {@link
 *       #newFixedLengthSource fixed-length}, {@link #newChunkedSource chunked}
 *       or {@link #newUnknownLengthSource unknown length}.
 *   <li>Read from and close that stream.
 * </ol>
 * <p>Exchanges that do not have a request body may skip creating and closing
 * the request body. Exchanges that do not have a response body must call {@link
 * #emptyResponseBody}.
 */
public final class HttpConnection {
  private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
  private static final int STATE_OPEN_REQUEST_BODY = 1;
  private static final int STATE_WRITING_REQUEST_BODY = 2;
  private static final int STATE_READ_RESPONSE_HEADERS = 3;
  private static final int STATE_OPEN_RESPONSE_BODY = 4;
  private static final int STATE_READING_RESPONSE_BODY = 5;
  private static final int STATE_CLOSED = 6;

  private static final int ON_IDLE_HOLD = 0;
  private static final int ON_IDLE_POOL = 1;
  private static final int ON_IDLE_CLOSE = 2;

  private final ConnectionPool pool;
  private final Connection connection;
  private final BufferedSource source;
  private final OutputStream out;

  private int state = STATE_IDLE;
  private int onIdle = ON_IDLE_HOLD;

  public HttpConnection(ConnectionPool pool, Connection connection, BufferedSource source,
      OutputStream out) {
    this.pool = pool;
    this.connection = connection;
    this.source = source;
    this.out = out;
  }

  /**
   * Configure this connection to put itself back into the connection pool when
   * the HTTP response body is exhausted.
   */
  public void poolOnIdle() {
    onIdle = ON_IDLE_POOL;

    // If we're already idle, go to the pool immediately.
    if (state == STATE_IDLE) {
      onIdle = ON_IDLE_HOLD; // Set the on idle policy back to the default.
      pool.recycle(connection);
    }
  }

  /**
   * Configure this connection to close itself when the HTTP response body is
   * exhausted.
   */
  public void closeOnIdle() throws IOException {
    onIdle = ON_IDLE_CLOSE;

    // If we're already idle, close immediately.
    if (state == STATE_IDLE) {
      state = STATE_CLOSED;
      connection.close();
    }
  }

  /** Returns true if this connection is closed. */
  public boolean isClosed() {
    return state == STATE_CLOSED;
  }

  public void flush() throws IOException {
    out.flush();
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  public void writeRequest(Headers headers, String requestLine) throws IOException {
    if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
    StringBuilder result = new StringBuilder(256);
    result.append(requestLine).append("\r\n");
    for (int i = 0; i < headers.size(); i ++) {
      result.append(headers.name(i))
          .append(": ")
          .append(headers.value(i))
          .append("\r\n");
    }
    result.append("\r\n");
    out.write(result.toString().getBytes("ISO-8859-1"));
    state = STATE_OPEN_REQUEST_BODY;
  }

  /** Parses bytes of a response header from an HTTP transport. */
  public Response.Builder readResponse() throws IOException {
    if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
      throw new IllegalStateException("state: " + state);
    }

    while (true) {
      String statusLineString = readLine();
      StatusLine statusLine = new StatusLine(statusLineString);

      Response.Builder responseBuilder = new Response.Builder()
          .statusLine(statusLine)
          .header(OkHeaders.SELECTED_TRANSPORT, Protocol.HTTP_11.name.utf8())
          .header(OkHeaders.SELECTED_PROTOCOL, Protocol.HTTP_11.name.utf8());

      Headers.Builder headersBuilder = new Headers.Builder();
      readHeaders(headersBuilder);
      responseBuilder.headers(headersBuilder.build());

      if (statusLine.code() != HTTP_CONTINUE) {
        state = STATE_OPEN_RESPONSE_BODY;
        return responseBuilder;
      }
    }
  }

  /** Reads headers or trailers into {@code builder}. */
  public void readHeaders(Headers.Builder builder) throws IOException {
    // parse the result headers until the first blank line
    for (String line; (line = readLine()).length() != 0; ) {
      builder.addLine(line);
    }
  }

  private String readLine() throws IOException {
    long newline = source.seek((byte) '\n', Deadline.NONE);

    if (newline > 0 && source.buffer.getByte(newline - 1) == '\r') {
      // Read everything until '\r\n', then skip the '\r\n'.
      String result = source.buffer.readUtf8((int) (newline - 1));
      source.buffer.skip(2);
      return result;

    } else {
      // Read everything until '\n', then skip the '\n'.
      String result = source.buffer.readUtf8((int) (newline));
      source.buffer.skip(1);
      return result;
    }
  }

  /**
   * Discards the response body so that the connection can be reused and the
   * cache entry can be completed. This needs to be done judiciously, since it
   * delays the current request in order to speed up a potential future request
   * that may never occur.
   */
  public boolean discard(Source in, int timeoutMillis) {
    Socket socket = connection.getSocket();
    try {
      int socketTimeout = socket.getSoTimeout();
      socket.setSoTimeout(timeoutMillis);
      try {
        return Util.skipAll(in, timeoutMillis);
      } finally {
        socket.setSoTimeout(socketTimeout);
      }
    } catch (IOException e) {
      return false;
    }
  }

  public OutputStream newChunkedOutputStream(int defaultChunkLength) {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new ChunkedOutputStream(out, defaultChunkLength);
  }

  public OutputStream newFixedLengthOutputStream(long contentLength) {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new FixedLengthOutputStream(out, contentLength);
  }

  public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READ_RESPONSE_HEADERS;
    requestBody.writeToSocket(out);
  }

  public Source newFixedLengthSource(CacheRequest cacheRequest, long length)
      throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new FixedLengthSource(cacheRequest, length);
  }

  /**
   * Call this to advance past a response body for HTTP responses that do not
   * have a response body.
   */
  public void emptyResponseBody() throws IOException {
    newFixedLengthSource(null, 0L); // Transition to STATE_IDLE.
  }

  public Source newChunkedSource(CacheRequest cacheRequest, HttpEngine httpEngine)
      throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new ChunkedSource(cacheRequest, httpEngine);
  }

  public Source newUnknownLengthSource(CacheRequest cacheRequest) throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new UnknownLengthSource(cacheRequest);
  }

  /** An HTTP body with a fixed length known in advance. */
  private final class FixedLengthOutputStream extends AbstractOutputStream {
    private final OutputStream socketOut;
    private long bytesRemaining;

    private FixedLengthOutputStream(OutputStream socketOut, long bytesRemaining) {
      this.socketOut = socketOut;
      this.bytesRemaining = bytesRemaining;
    }

    @Override public void write(byte[] buffer, int offset, int count) throws IOException {
      checkNotClosed();
      checkOffsetAndCount(buffer.length, offset, count);
      if (count > bytesRemaining) {
        throw new ProtocolException("expected " + bytesRemaining + " bytes but received " + count);
      }
      socketOut.write(buffer, offset, count);
      bytesRemaining -= count;
    }

    @Override public void flush() throws IOException {
      if (closed) {
        return; // don't throw; this stream might have been closed on the caller's behalf
      }
      socketOut.flush();
    }

    @Override public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      if (bytesRemaining > 0) {
        throw new ProtocolException("unexpected end of stream");
      }
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  private static final byte[] CRLF = { '\r', '\n' };
  private static final byte[] HEX_DIGITS = {
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };
  private static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n' };

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. Chunks are
   * buffered until {@code maxChunkLength} bytes are ready, at which point the
   * chunk is written and the buffer is cleared.
   */
  private final class ChunkedOutputStream extends AbstractOutputStream {
    /** Scratch space for up to 8 hex digits, and then a constant CRLF. */
    private final byte[] hex = { 0, 0, 0, 0, 0, 0, 0, 0, '\r', '\n' };

    private final OutputStream socketOut;
    private final int maxChunkLength;
    private final ByteArrayOutputStream bufferedChunk;

    private ChunkedOutputStream(OutputStream socketOut, int maxChunkLength) {
      this.socketOut = socketOut;
      this.maxChunkLength = Math.max(1, dataLength(maxChunkLength));
      this.bufferedChunk = new ByteArrayOutputStream(maxChunkLength);
    }

    /**
     * Returns the amount of data that can be transmitted in a chunk whose total
     * length (data+headers) is {@code dataPlusHeaderLength}. This is presumably
     * useful to match sizes with wire-protocol packets.
     */
    private int dataLength(int dataPlusHeaderLength) {
      int headerLength = 4; // "\r\n" after the size plus another "\r\n" after the data
      for (int i = dataPlusHeaderLength - headerLength; i > 0; i >>= 4) {
        headerLength++;
      }
      return dataPlusHeaderLength - headerLength;
    }

    @Override public synchronized void write(byte[] buffer, int offset, int count)
        throws IOException {
      checkNotClosed();
      checkOffsetAndCount(buffer.length, offset, count);

      while (count > 0) {
        int numBytesWritten;

        if (bufferedChunk.size() > 0 || count < maxChunkLength) {
          // fill the buffered chunk and then maybe write that to the stream
          numBytesWritten = Math.min(count, maxChunkLength - bufferedChunk.size());
          // TODO: skip unnecessary copies from buffer->bufferedChunk?
          bufferedChunk.write(buffer, offset, numBytesWritten);
          if (bufferedChunk.size() == maxChunkLength) {
            writeBufferedChunkToSocket();
          }
        } else {
          // write a single chunk of size maxChunkLength to the stream
          numBytesWritten = maxChunkLength;
          writeHex(numBytesWritten);
          socketOut.write(buffer, offset, numBytesWritten);
          socketOut.write(CRLF);
        }

        offset += numBytesWritten;
        count -= numBytesWritten;
      }
    }

    /**
     * Equivalent to, but cheaper than writing Integer.toHexString().getBytes()
     * followed by CRLF.
     */
    private void writeHex(int i) throws IOException {
      int cursor = 8;
      do {
        hex[--cursor] = HEX_DIGITS[i & 0xf];
      } while ((i >>>= 4) != 0);
      socketOut.write(hex, cursor, hex.length - cursor);
    }

    @Override public synchronized void flush() throws IOException {
      if (closed) {
        return; // don't throw; this stream might have been closed on the caller's behalf
      }
      writeBufferedChunkToSocket();
      socketOut.flush();
    }

    @Override public synchronized void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      writeBufferedChunkToSocket();
      socketOut.write(FINAL_CHUNK);
      state = STATE_READ_RESPONSE_HEADERS;
    }

    private void writeBufferedChunkToSocket() throws IOException {
      int size = bufferedChunk.size();
      if (size <= 0) {
        return;
      }

      writeHex(size);
      bufferedChunk.writeTo(socketOut);
      bufferedChunk.reset();
      socketOut.write(CRLF);
    }
  }

  private class AbstractSource {
    private final CacheRequest cacheRequest;
    protected final OutputStream cacheBody;
    protected boolean closed;

    AbstractSource(CacheRequest cacheRequest) throws IOException {
      // Some apps return a null body; for compatibility we treat that like a null cache request.
      OutputStream cacheBody = cacheRequest != null ? cacheRequest.getBody() : null;
      if (cacheBody == null) {
        cacheRequest = null;
      }

      this.cacheBody = cacheBody;
      this.cacheRequest = cacheRequest;
    }

    /** Copy the last {@code byteCount} bytes of {@code source} to the cache body. */
    protected final void cacheWrite(OkBuffer source, long byteCount) throws IOException {
      if (cacheBody != null) {
        OkBuffers.copy(source, source.byteCount() - byteCount, byteCount, cacheBody);
      }
    }

    /**
     * Closes the cache entry and makes the socket available for reuse. This
     * should be invoked when the end of the body has been reached.
     */
    protected final void endOfInput() throws IOException {
      if (state != STATE_READING_RESPONSE_BODY) throw new IllegalStateException("state: " + state);

      if (cacheRequest != null) {
        cacheBody.close();
      }

      state = STATE_IDLE;
      if (onIdle == ON_IDLE_POOL) {
        onIdle = ON_IDLE_HOLD; // Set the on idle policy back to the default.
        pool.recycle(connection);
      } else if (onIdle == ON_IDLE_CLOSE) {
        state = STATE_CLOSED;
        connection.close();
      }
    }

    /**
     * Calls abort on the cache entry and disconnects the socket. This
     * should be invoked when the connection is closed unexpectedly to
     * invalidate the cache entry and to prevent the HTTP connection from
     * being reused. HTTP messages are sent in serial so whenever a message
     * cannot be read to completion, subsequent messages cannot be read
     * either and the connection must be discarded.
     *
     * <p>An earlier implementation skipped the remaining bytes, but this
     * requires that the entire transfer be completed. If the intention was
     * to cancel the transfer, closing the connection is the only solution.
     */
    protected final void unexpectedEndOfInput() {
      if (cacheRequest != null) {
        cacheRequest.abort();
      }
      Util.closeQuietly(connection);
      state = STATE_CLOSED;
    }
  }

  /** An HTTP body with a fixed length specified in advance. */
  private class FixedLengthSource extends AbstractSource implements Source {
    private long bytesRemaining;

    public FixedLengthSource(CacheRequest cacheRequest, long length) throws IOException {
      super(cacheRequest);
      bytesRemaining = length;
      if (bytesRemaining == 0) {
        endOfInput();
      }
    }

    @Override public long read(OkBuffer sink, long byteCount, Deadline deadline)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (bytesRemaining == 0) return -1;

      long read = source.read(sink, Math.min(bytesRemaining, byteCount), deadline);
      if (read == -1) {
        unexpectedEndOfInput(); // the server didn't supply the promised content length
        throw new ProtocolException("unexpected end of stream");
      }

      bytesRemaining -= read;
      cacheWrite(sink, read);
      if (bytesRemaining == 0) {
        endOfInput();
      }
      return read;
    }

    @Override public void close(Deadline deadline) throws IOException {
      if (closed) return;

      if (bytesRemaining != 0 && !discard(this, DISCARD_STREAM_TIMEOUT_MILLIS)) {
        unexpectedEndOfInput();
      }

      closed = true;
    }
  }

  /** An HTTP body with alternating chunk sizes and chunk bodies. */
  private class ChunkedSource extends AbstractSource implements Source {
    private static final int NO_CHUNK_YET = -1;
    private int bytesRemainingInChunk = NO_CHUNK_YET;
    private boolean hasMoreChunks = true;
    private final HttpEngine httpEngine;

    ChunkedSource(CacheRequest cacheRequest, HttpEngine httpEngine) throws IOException {
      super(cacheRequest);
      this.httpEngine = httpEngine;
    }

    @Override public long read(
        OkBuffer sink, long byteCount, Deadline deadline) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (!hasMoreChunks) return -1;

      if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize();
        if (!hasMoreChunks) return -1;
      }

      long read = source.read(sink, Math.min(byteCount, bytesRemainingInChunk), deadline);
      if (read == -1) {
        unexpectedEndOfInput(); // the server didn't supply the promised chunk length
        throw new IOException("unexpected end of stream");
      }
      bytesRemainingInChunk -= read;
      cacheWrite(sink, read);
      return read;
    }

    private void readChunkSize() throws IOException {
      // read the suffix of the previous chunk
      if (bytesRemainingInChunk != NO_CHUNK_YET) {
        readLine();
      }
      String chunkSizeString = readLine();
      int index = chunkSizeString.indexOf(";");
      if (index != -1) {
        chunkSizeString = chunkSizeString.substring(0, index);
      }
      try {
        bytesRemainingInChunk = Integer.parseInt(chunkSizeString.trim(), 16);
      } catch (NumberFormatException e) {
        throw new ProtocolException("Expected a hex chunk size but was " + chunkSizeString);
      }
      if (bytesRemainingInChunk == 0) {
        hasMoreChunks = false;
        Headers.Builder trailersBuilder = new Headers.Builder();
        readHeaders(trailersBuilder);
        httpEngine.receiveHeaders(trailersBuilder.build());
        endOfInput();
      }
    }

    @Override public void close(Deadline deadline) throws IOException {
      if (closed) return;
      if (hasMoreChunks && !discard(this, DISCARD_STREAM_TIMEOUT_MILLIS)) {
        unexpectedEndOfInput();
      }
      closed = true;
    }
  }

  /** An HTTP message body terminated by the end of the underlying stream. */
  class UnknownLengthSource extends AbstractSource implements Source {
    private boolean inputExhausted;

    UnknownLengthSource(CacheRequest cacheRequest) throws IOException {
      super(cacheRequest);
    }

    @Override public long read(OkBuffer sink, long byteCount, Deadline deadline)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = source.read(sink, byteCount, deadline);
      if (read == -1) {
        inputExhausted = true;
        endOfInput();
        return -1;
      }
      cacheWrite(sink, read);
      return read;
    }

    @Override public void close(Deadline deadline) throws IOException {
      if (closed) return;
      // TODO: discard unknown length streams for best caching?
      if (!inputExhausted) {
        unexpectedEndOfInput();
      }
      closed = true;
    }
  }
}
