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
import com.squareup.okhttp.internal.AbstractOutputStream;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.ProtocolException;
import java.net.Socket;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

public final class HttpTransport implements Transport {
  /**
   * The timeout to use while discarding a stream of input data. Since this is
   * used for connection reuse, this timeout should be significantly less than
   * the time it takes to establish a new connection.
   */
  private static final int DISCARD_STREAM_TIMEOUT_MILLIS = 100;

  public static final int DEFAULT_CHUNK_LENGTH = 1024;

  private final HttpEngine httpEngine;
  private final InputStream socketIn;
  private final OutputStream socketOut;

  /**
   * This stream buffers the request headers and the request body when their
   * combined size is less than MAX_REQUEST_BUFFER_LENGTH. By combining them
   * we can save socket writes, which in turn saves a packet transmission.
   * This is socketOut if the request size is large or unknown.
   */
  private OutputStream requestOut;

  public HttpTransport(HttpEngine httpEngine, OutputStream outputStream, InputStream inputStream) {
    this.httpEngine = httpEngine;
    this.socketOut = outputStream;
    this.requestOut = outputStream;
    this.socketIn = inputStream;
  }

  @Override public OutputStream createRequestBody() throws IOException {
    boolean chunked = httpEngine.requestHeaders.isChunked();
    if (!chunked
        && httpEngine.policy.getChunkLength() > 0
        && httpEngine.connection.getHttpMinorVersion() != 0) {
      httpEngine.requestHeaders.setChunked();
      chunked = true;
    }

    // Stream a request body of unknown length.
    if (chunked) {
      int chunkLength = httpEngine.policy.getChunkLength();
      if (chunkLength == -1) {
        chunkLength = DEFAULT_CHUNK_LENGTH;
      }
      writeRequestHeaders();
      return new ChunkedOutputStream(requestOut, chunkLength);
    }

    // Stream a request body of a known length.
    long fixedContentLength = httpEngine.policy.getFixedContentLength();
    if (fixedContentLength != -1) {
      httpEngine.requestHeaders.setContentLength(fixedContentLength);
      writeRequestHeaders();
      return new FixedLengthOutputStream(requestOut, fixedContentLength);
    }

    long contentLength = httpEngine.requestHeaders.getContentLength();
    if (contentLength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Use setFixedLengthStreamingMode() or "
          + "setChunkedStreamingMode() for requests larger than 2 GiB.");
    }

    // Buffer a request body of a known length.
    if (contentLength != -1) {
      writeRequestHeaders();
      return new RetryableOutputStream((int) contentLength);
    }

    // Buffer a request body of an unknown length. Don't write request
    // headers until the entire body is ready; otherwise we can't set the
    // Content-Length header correctly.
    return new RetryableOutputStream();
  }

  @Override public void flushRequest() throws IOException {
    requestOut.flush();
    requestOut = socketOut;
  }

  @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    requestBody.writeToSocket(requestOut);
  }

  /**
   * Prepares the HTTP headers and sends them to the server.
   *
   * <p>For streaming requests with a body, headers must be prepared
   * <strong>before</strong> the output stream has been written to. Otherwise
   * the body would need to be buffered!
   *
   * <p>For non-streaming requests with a body, headers must be prepared
   * <strong>after</strong> the output stream has been written to and closed.
   * This ensures that the {@code Content-Length} header field receives the
   * proper value.
   */
  public void writeRequestHeaders() throws IOException {
    httpEngine.writingRequestHeaders();
    RawHeaders headersToSend = httpEngine.requestHeaders.getHeaders();
    byte[] bytes = headersToSend.toBytes();
    requestOut.write(bytes);
  }

  @Override public ResponseHeaders readResponseHeaders() throws IOException {
    RawHeaders rawHeaders = RawHeaders.fromBytes(socketIn);
    httpEngine.connection.setHttpMinorVersion(rawHeaders.getHttpMinorVersion());
    httpEngine.receiveHeaders(rawHeaders);

    ResponseHeaders headers = new ResponseHeaders(httpEngine.uri, rawHeaders);
    headers.setTransport("http/1.1");
    return headers;
  }

  public boolean makeReusable(boolean streamCanceled, OutputStream requestBodyOut,
      InputStream responseBodyIn) {
    if (streamCanceled) {
      return false;
    }

    // We cannot reuse sockets that have incomplete output.
    if (requestBodyOut != null && !((AbstractOutputStream) requestBodyOut).isClosed()) {
      return false;
    }

    // If the request specified that the connection shouldn't be reused, don't reuse it.
    if (httpEngine.requestHeaders.hasConnectionClose()) {
      return false;
    }

    // If the response specified that the connection shouldn't be reused, don't reuse it.
    if (httpEngine.responseHeaders != null && httpEngine.responseHeaders.hasConnectionClose()) {
      return false;
    }

    if (responseBodyIn instanceof UnknownLengthHttpInputStream) {
      return false;
    }

    if (responseBodyIn != null) {
      return discardStream(httpEngine, responseBodyIn);
    }

    return true;
  }

  /**
   * Discards the response body so that the connection can be reused. This
   * needs to be done judiciously, since it delays the current request in
   * order to speed up a potential future request that may never occur.
   *
   * <p>A stream may be discarded to encourage response caching (a response
   * cannot be cached unless it is consumed completely) or to enable connection
   * reuse.
   */
  private static boolean discardStream(HttpEngine httpEngine, InputStream responseBodyIn) {
    Connection connection = httpEngine.connection;
    if (connection == null) return false;
    Socket socket = connection.getSocket();
    if (socket == null) return false;
    try {
      int socketTimeout = socket.getSoTimeout();
      socket.setSoTimeout(DISCARD_STREAM_TIMEOUT_MILLIS);
      try {
        Util.skipAll(responseBodyIn);
        return true;
      } finally {
        socket.setSoTimeout(socketTimeout);
      }
    } catch (IOException e) {
      return false;
    }
  }

  @Override public InputStream getTransferStream(CacheRequest cacheRequest) throws IOException {
    if (!httpEngine.hasResponseBody()) {
      return new FixedLengthInputStream(socketIn, cacheRequest, httpEngine, 0);
    }

    if (httpEngine.responseHeaders.isChunked()) {
      return new ChunkedInputStream(socketIn, cacheRequest, this);
    }

    if (httpEngine.responseHeaders.getContentLength() != -1) {
      return new FixedLengthInputStream(socketIn, cacheRequest, httpEngine,
          httpEngine.responseHeaders.getContentLength());
    }

    // Wrap the input stream from the connection (rather than just returning
    // "socketIn" directly here), so that we can control its use after the
    // reference escapes.
    return new UnknownLengthHttpInputStream(socketIn, cacheRequest, httpEngine);
  }

  /** An HTTP body with a fixed length known in advance. */
  private static final class FixedLengthOutputStream extends AbstractOutputStream {
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
    }
  }

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. Chunks are
   * buffered until {@code maxChunkLength} bytes are ready, at which point the
   * chunk is written and the buffer is cleared.
   */
  private static final class ChunkedOutputStream extends AbstractOutputStream {
    private static final byte[] CRLF = { '\r', '\n' };
    private static final byte[] HEX_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    private static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n' };

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

  /** An HTTP body with a fixed length specified in advance. */
  private static class FixedLengthInputStream extends AbstractHttpInputStream {
    private long bytesRemaining;

    public FixedLengthInputStream(InputStream is, CacheRequest cacheRequest, HttpEngine httpEngine,
        long length) throws IOException {
      super(is, httpEngine, cacheRequest);
      bytesRemaining = length;
      if (bytesRemaining == 0) {
        endOfInput();
      }
    }

    @Override public int read(byte[] buffer, int offset, int count) throws IOException {
      checkOffsetAndCount(buffer.length, offset, count);
      checkNotClosed();
      if (bytesRemaining == 0) {
        return -1;
      }
      int read = in.read(buffer, offset, (int) Math.min(count, bytesRemaining));
      if (read == -1) {
        unexpectedEndOfInput(); // the server didn't supply the promised content length
        throw new ProtocolException("unexpected end of stream");
      }
      bytesRemaining -= read;
      cacheWrite(buffer, offset, read);
      if (bytesRemaining == 0) {
        endOfInput();
      }
      return read;
    }

    @Override public int available() throws IOException {
      checkNotClosed();
      return bytesRemaining == 0 ? 0 : (int) Math.min(in.available(), bytesRemaining);
    }

    @Override public void close() throws IOException {
      if (closed) {
        return;
      }
      if (bytesRemaining != 0 && !discardStream(httpEngine, this)) {
        unexpectedEndOfInput();
      }
      closed = true;
    }
  }

  /** An HTTP body with alternating chunk sizes and chunk bodies. */
  private static class ChunkedInputStream extends AbstractHttpInputStream {
    private static final int NO_CHUNK_YET = -1;
    private final HttpTransport transport;
    private int bytesRemainingInChunk = NO_CHUNK_YET;
    private boolean hasMoreChunks = true;

    ChunkedInputStream(InputStream is, CacheRequest cacheRequest, HttpTransport transport)
        throws IOException {
      super(is, transport.httpEngine, cacheRequest);
      this.transport = transport;
    }

    @Override public int read(byte[] buffer, int offset, int count) throws IOException {
      checkOffsetAndCount(buffer.length, offset, count);
      checkNotClosed();

      if (!hasMoreChunks) {
        return -1;
      }
      if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize();
        if (!hasMoreChunks) {
          return -1;
        }
      }
      int read = in.read(buffer, offset, Math.min(count, bytesRemainingInChunk));
      if (read == -1) {
        unexpectedEndOfInput(); // the server didn't supply the promised chunk length
        throw new IOException("unexpected end of stream");
      }
      bytesRemainingInChunk -= read;
      cacheWrite(buffer, offset, read);
      return read;
    }

    private void readChunkSize() throws IOException {
      // read the suffix of the previous chunk
      if (bytesRemainingInChunk != NO_CHUNK_YET) {
        Util.readAsciiLine(in);
      }
      String chunkSizeString = Util.readAsciiLine(in);
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
        RawHeaders rawResponseHeaders = httpEngine.responseHeaders.getHeaders();
        RawHeaders.readHeaders(transport.socketIn, rawResponseHeaders);
        httpEngine.receiveHeaders(rawResponseHeaders);
        endOfInput();
      }
    }

    @Override public int available() throws IOException {
      checkNotClosed();
      if (!hasMoreChunks || bytesRemainingInChunk == NO_CHUNK_YET) {
        return 0;
      }
      return Math.min(in.available(), bytesRemainingInChunk);
    }

    @Override public void close() throws IOException {
      if (closed) {
        return;
      }
      if (hasMoreChunks && !discardStream(httpEngine, this)) {
        unexpectedEndOfInput();
      }
      closed = true;
    }
  }
}
