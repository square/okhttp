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
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_CONTINUE;
import static com.squareup.okhttp.internal.http.Transport.DISCARD_STREAM_TIMEOUT_MILLIS;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class
 * strictly enforces the following lifecycle:
 * <ol>
 *   <li>{@link #writeRequest Send request headers}.
 *   <li>Open a sink to write the request body. Either {@link
 *       #newFixedLengthSink fixed-length} or {@link #newChunkedSink chunked}.
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
  private final Socket socket;
  private final BufferedSource source;
  private final BufferedSink sink;

  private int state = STATE_IDLE;
  private int onIdle = ON_IDLE_HOLD;

  public HttpConnection(ConnectionPool pool, Connection connection, Socket socket,
      int readTimeout, int writeTimeout)
      throws IOException {
    this.pool = pool;
    this.connection = connection;
    this.socket = socket;
    this.source = Okio.buffer(Okio.source(socket));
    if (readTimeout != 0) {
      source.timeout().timeout(readTimeout, TimeUnit.MILLISECONDS);
    }
    this.sink = Okio.buffer(Okio.sink(socket));
    if (writeTimeout != 0) {
      sink.timeout().timeout(writeTimeout, TimeUnit.MILLISECONDS);
    }
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

  public void closeIfOwnedBy(Object owner) throws IOException {
    connection.closeIfOwnedBy(owner);
  }

  public void flush() throws IOException {
    sink.flush();
  }

  /** Returns the number of buffered bytes immediately readable. */
  public long bufferSize() {
    return source.buffer().size();
  }

  /** Test for a stale socket. */
  public boolean isReadable() {
    try {
      int readTimeout = socket.getSoTimeout();
      try {
        socket.setSoTimeout(1);
        if (source.exhausted()) {
          return false; // Stream is exhausted; socket is closed.
        }
        return true;
      } finally {
        socket.setSoTimeout(readTimeout);
      }
    } catch (SocketTimeoutException ignored) {
      return true; // Read timed out; socket is good.
    } catch (IOException e) {
      return false; // Couldn't read; socket is closed.
    }
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  public void writeRequest(Headers headers, String requestLine) throws IOException {
    if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
    sink.writeUtf8(requestLine).writeUtf8("\r\n");
    for (int i = 0; i < headers.size(); i ++) {
      sink.writeUtf8(headers.name(i))
          .writeUtf8(": ")
          .writeUtf8(headers.value(i))
          .writeUtf8("\r\n");
    }
    sink.writeUtf8("\r\n");
    state = STATE_OPEN_REQUEST_BODY;
  }

  /** Parses bytes of a response header from an HTTP transport. */
  public Response.Builder readResponse() throws IOException {
    if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
      throw new IllegalStateException("state: " + state);
    }

    while (true) {
      String statusLineString = source.readUtf8LineStrict();
      StatusLine statusLine = new StatusLine(statusLineString);

      Response.Builder responseBuilder = new Response.Builder()
          .statusLine(statusLine)
          .header(OkHeaders.SELECTED_PROTOCOL, Protocol.HTTP_11.toString());

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
    for (String line; (line = source.readUtf8LineStrict()).length() != 0; ) {
      builder.addLine(line);
    }
  }

  /**
   * Discards the response body so that the connection can be reused and the
   * cache entry can be completed. This needs to be done judiciously, since it
   * delays the current request in order to speed up a potential future request
   * that may never occur.
   */
  public boolean discard(Source in, int timeoutMillis) {
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

  public Sink newChunkedSink() {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new ChunkedSink();
  }

  public Sink newFixedLengthSink(long contentLength) {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new FixedLengthSink(contentLength);
  }

  public void writeRequestBody(RetryableSink requestBody) throws IOException {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READ_RESPONSE_HEADERS;
    requestBody.writeToSocket(sink);
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
  private final class FixedLengthSink implements Sink {
    private boolean closed;
    private long bytesRemaining;

    private FixedLengthSink(long bytesRemaining) {
      this.bytesRemaining = bytesRemaining;
    }

    @Override public Timeout timeout() {
      return sink.timeout();
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      checkOffsetAndCount(source.size(), 0, byteCount);
      if (byteCount > bytesRemaining) {
        throw new ProtocolException("expected " + bytesRemaining
            + " bytes but received " + byteCount);
      }
      sink.write(source, byteCount);
      bytesRemaining -= byteCount;
    }

    @Override public void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (bytesRemaining > 0) throw new ProtocolException("unexpected end of stream");
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  private static final String CRLF = "\r\n";
  private static final byte[] HEX_DIGITS = {
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };
  private static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n' };

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. It is the
   * caller's responsibility to buffer chunks; typically by using a buffered
   * sink with this sink.
   */
  private final class ChunkedSink implements Sink {
    /** Scratch space for up to 16 hex digits, and then a constant CRLF. */
    private final byte[] hex = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '\r', '\n' };

    private boolean closed;

    @Override public Timeout timeout() {
      return sink.timeout();
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (byteCount == 0) return;

      writeHex(byteCount);
      sink.write(source, byteCount);
      sink.writeUtf8(CRLF);
    }

    @Override public synchronized void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      sink.write(FINAL_CHUNK);
      state = STATE_READ_RESPONSE_HEADERS;
    }

    /**
     * Equivalent to, but cheaper than writing Long.toHexString().getBytes()
     * followed by CRLF.
     */
    private void writeHex(long i) throws IOException {
      int cursor = 16;
      do {
        hex[--cursor] = HEX_DIGITS[((int) (i & 0xf))];
      } while ((i >>>= 4) != 0);
      sink.write(hex, cursor, hex.length - cursor);
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
    protected final void cacheWrite(Buffer source, long byteCount) throws IOException {
      if (cacheBody != null) {
        source.copyTo(cacheBody, source.size() - byteCount, byteCount);
      }
    }

    /**
     * Closes the cache entry and makes the socket available for reuse. This
     * should be invoked when the end of the body has been reached.
     */
    protected final void endOfInput(boolean recyclable) throws IOException {
      if (state != STATE_READING_RESPONSE_BODY) throw new IllegalStateException("state: " + state);

      if (cacheRequest != null) {
        cacheBody.close();
      }

      state = STATE_IDLE;
      if (recyclable && onIdle == ON_IDLE_POOL) {
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
        endOfInput(true);
      }
    }

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (bytesRemaining == 0) return -1;

      long read = source.read(sink, Math.min(bytesRemaining, byteCount));
      if (read == -1) {
        unexpectedEndOfInput(); // the server didn't supply the promised content length
        throw new ProtocolException("unexpected end of stream");
      }

      bytesRemaining -= read;
      cacheWrite(sink, read);
      if (bytesRemaining == 0) {
        endOfInput(true);
      }
      return read;
    }

    @Override public Timeout timeout() {
      return source.timeout();
    }

    @Override public void close() throws IOException {
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
        Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (!hasMoreChunks) return -1;

      if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize();
        if (!hasMoreChunks) return -1;
      }

      long read = source.read(sink, Math.min(byteCount, bytesRemainingInChunk));
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
        source.readUtf8LineStrict();
      }
      String chunkSizeString = source.readUtf8LineStrict();
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
        endOfInput(true);
      }
    }

    @Override public Timeout timeout() {
      return source.timeout();
    }

    @Override public void close() throws IOException {
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

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = source.read(sink, byteCount);
      if (read == -1) {
        inputExhausted = true;
        endOfInput(false);
        return -1;
      }
      cacheWrite(sink, read);
      return read;
    }

    @Override public Timeout timeout() {
      return source.timeout();
    }

    @Override public void close() throws IOException {
      if (closed) return;
      // TODO: discard unknown length streams for best caching?
      if (!inputExhausted) {
        unexpectedEndOfInput();
      }
      closed = true;
    }
  }
}
