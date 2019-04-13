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
package okhttp3.internal.http1;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.http.ExchangeCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingTimeout;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.checkOffsetAndCount;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 *
 * <ol>
 *     <li>{@linkplain #writeRequest Send request headers}.
 *     <li>Open a sink to write the request body. Either {@linkplain #newKnownLengthSink known
 *         length} or {@link #newChunkedSink chunked}.
 *     <li>Write to and then close that sink.
 *     <li>{@linkplain #readResponseHeaders Read response headers}.
 *     <li>Open a source to read the response body. Either {@linkplain #newFixedLengthSource
 *         fixed-length}, {@linkplain #newChunkedSource chunked} or {@linkplain
 *         #newUnknownLengthSource unknown length}.
 *     <li>Read from and close that source.
 * </ol>
 *
 * <p>Exchanges that do not have a request body may skip creating and closing the request body.
 * Exchanges that do not have a response body can call {@link #newFixedLengthSource(long)
 * newFixedLengthSource(0)} and may skip reading and closing that source.
 */
public final class Http1ExchangeCodec implements ExchangeCodec {
  private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
  private static final int STATE_OPEN_REQUEST_BODY = 1;
  private static final int STATE_WRITING_REQUEST_BODY = 2;
  private static final int STATE_READ_RESPONSE_HEADERS = 3;
  private static final int STATE_OPEN_RESPONSE_BODY = 4;
  private static final int STATE_READING_RESPONSE_BODY = 5;
  private static final int STATE_CLOSED = 6;
  private static final int HEADER_LIMIT = 256 * 1024;

  /** The client that configures this stream. May be null for HTTPS proxy tunnels. */
  private final OkHttpClient client;

  /** The connection that carries this stream. */
  private final RealConnection realConnection;

  private final BufferedSource source;
  private final BufferedSink sink;
  private int state = STATE_IDLE;
  private long headerLimit = HEADER_LIMIT;

  /**
   * Received trailers. Null unless the response body uses chunked transfer-encoding and includes
   * trailers. Undefined until the end of the response body.
   */
  private Headers trailers;

  public Http1ExchangeCodec(OkHttpClient client, RealConnection realConnection,
      BufferedSource source, BufferedSink sink) {
    this.client = client;
    this.realConnection = realConnection;
    this.source = source;
    this.sink = sink;
  }

  @Override public RealConnection connection() {
    return realConnection;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) throws IOException {
    if (request.body() != null && request.body().isDuplex()) {
      throw new ProtocolException("Duplex connections are not supported for HTTP/1");
    }

    if ("chunked".equalsIgnoreCase(request.header("Transfer-Encoding"))) {
      // Stream a request body of unknown length.
      return newChunkedSink();
    }

    if (contentLength != -1L) {
      // Stream a request body of a known length.
      return newKnownLengthSink();
    }

    throw new IllegalStateException(
        "Cannot stream a request body without chunked encoding or a known content length!");
  }

  @Override public void cancel() {
    if (realConnection != null) realConnection.cancel();
  }

  /**
   * Prepares the HTTP headers and sends them to the server.
   *
   * <p>For streaming requests with a body, headers must be prepared <strong>before</strong> the
   * output stream has been written to. Otherwise the body would need to be buffered!
   *
   * <p>For non-streaming requests with a body, headers must be prepared <strong>after</strong> the
   * output stream has been written to and closed. This ensures that the {@code Content-Length}
   * header field receives the proper value.
   */
  @Override public void writeRequestHeaders(Request request) throws IOException {
    String requestLine = RequestLine.get(
        request, realConnection.route().proxy().type());
    writeRequest(request.headers(), requestLine);
  }

  @Override public long reportedContentLength(Response response) {
    if (!HttpHeaders.hasBody(response)) {
      return 0L;
    }

    if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
      return -1L;
    }

    return HttpHeaders.contentLength(response);
  }

  @Override public Source openResponseBodySource(Response response) {
    if (!HttpHeaders.hasBody(response)) {
      return newFixedLengthSource(0);
    }

    if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
      return newChunkedSource(response.request().url());
    }

    long contentLength = HttpHeaders.contentLength(response);
    if (contentLength != -1) {
      return newFixedLengthSource(contentLength);
    }

    return newUnknownLengthSource();
  }

  @Override public Headers trailers() {
    if (state != STATE_CLOSED) {
      throw new IllegalStateException("too early; can't read the trailers yet");
    }
    return trailers != null ? trailers : Util.EMPTY_HEADERS;
  }

  /** Returns true if this connection is closed. */
  public boolean isClosed() {
    return state == STATE_CLOSED;
  }

  @Override public void flushRequest() throws IOException {
    sink.flush();
  }

  @Override public void finishRequest() throws IOException {
    sink.flush();
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  public void writeRequest(Headers headers, String requestLine) throws IOException {
    if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
    sink.writeUtf8(requestLine).writeUtf8("\r\n");
    for (int i = 0, size = headers.size(); i < size; i++) {
      sink.writeUtf8(headers.name(i))
          .writeUtf8(": ")
          .writeUtf8(headers.value(i))
          .writeUtf8("\r\n");
    }
    sink.writeUtf8("\r\n");
    state = STATE_OPEN_REQUEST_BODY;
  }

  @Override public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
      throw new IllegalStateException("state: " + state);
    }

    try {
      StatusLine statusLine = StatusLine.parse(readHeaderLine());

      Response.Builder responseBuilder = new Response.Builder()
          .protocol(statusLine.protocol)
          .code(statusLine.code)
          .message(statusLine.message)
          .headers(readHeaders());

      if (expectContinue && statusLine.code == HTTP_CONTINUE) {
        return null;
      } else if (statusLine.code == HTTP_CONTINUE) {
        state = STATE_READ_RESPONSE_HEADERS;
        return responseBuilder;
      }

      state = STATE_OPEN_RESPONSE_BODY;
      return responseBuilder;
    } catch (EOFException e) {
      // Provide more context if the server ends the stream before sending a response.
      String address = "unknown";
      if (realConnection != null) {
        address = realConnection.route().address().url().redact();
      }
      throw new IOException("unexpected end of stream on "
          + address, e);
    }
  }

  private String readHeaderLine() throws IOException {
    String line = source.readUtf8LineStrict(headerLimit);
    headerLimit -= line.length();
    return line;
  }

  /** Reads headers or trailers. */
  private Headers readHeaders() throws IOException {
    Headers.Builder headers = new Headers.Builder();
    // parse the result headers until the first blank line
    for (String line; (line = readHeaderLine()).length() != 0; ) {
      Internal.instance.addLenient(headers, line);
    }
    return headers.build();
  }

  private Sink newChunkedSink() {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new ChunkedSink();
  }

  private Sink newKnownLengthSink() {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new KnownLengthSink();
  }

  private Source newFixedLengthSource(long length) {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new FixedLengthSource(length);
  }

  private Source newChunkedSource(HttpUrl url) {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new ChunkedSource(url);
  }

  private Source newUnknownLengthSource() {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    realConnection.noNewExchanges();
    return new UnknownLengthSource();
  }

  /**
   * Sets the delegate of {@code timeout} to {@link Timeout#NONE} and resets its underlying timeout
   * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
   * connections.
   */
  private void detachTimeout(ForwardingTimeout timeout) {
    Timeout oldDelegate = timeout.delegate();
    timeout.setDelegate(Timeout.NONE);
    oldDelegate.clearDeadline();
    oldDelegate.clearTimeout();
  }

  /**
   * The response body from a CONNECT should be empty, but if it is not then we should consume it
   * before proceeding.
   */
  public void skipConnectBody(Response response) throws IOException {
    long contentLength = HttpHeaders.contentLength(response);
    if (contentLength == -1L) return;
    Source body = newFixedLengthSource(contentLength);
    Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
    body.close();
  }

  /** An HTTP request body. */
  private final class KnownLengthSink implements Sink {
    private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
    private boolean closed;

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      checkOffsetAndCount(source.size(), 0, byteCount);
      sink.write(source, byteCount);
    }

    @Override public void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      detachTimeout(timeout);
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
   * to buffer chunks; typically by using a buffered sink with this sink.
   */
  private final class ChunkedSink implements Sink {
    private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
    private boolean closed;

    ChunkedSink() {
    }

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (byteCount == 0) return;

      sink.writeHexadecimalUnsignedLong(byteCount);
      sink.writeUtf8("\r\n");
      sink.write(source, byteCount);
      sink.writeUtf8("\r\n");
    }

    @Override public synchronized void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      sink.writeUtf8("0\r\n\r\n");
      detachTimeout(timeout);
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  private abstract class AbstractSource implements Source {
    protected final ForwardingTimeout timeout = new ForwardingTimeout(source.timeout());
    protected boolean closed;

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      try {
        return source.read(sink, byteCount);
      } catch (IOException e) {
        realConnection.noNewExchanges();
        responseBodyComplete();
        throw e;
      }
    }

    /**
     * Closes the cache entry and makes the socket available for reuse. This should be invoked when
     * the end of the body has been reached.
     */
    final void responseBodyComplete() {
      if (state == STATE_CLOSED) return;
      if (state != STATE_READING_RESPONSE_BODY) throw new IllegalStateException("state: " + state);

      detachTimeout(timeout);

      state = STATE_CLOSED;
    }
  }

  /** An HTTP body with a fixed length specified in advance. */
  private class FixedLengthSource extends AbstractSource {
    private long bytesRemaining;

    FixedLengthSource(long length) {
      bytesRemaining = length;
      if (bytesRemaining == 0) {
        responseBodyComplete();
      }
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (bytesRemaining == 0) return -1;

      long read = super.read(sink, Math.min(bytesRemaining, byteCount));
      if (read == -1) {
        realConnection.noNewExchanges(); // The server didn't supply the promised content length.
        ProtocolException e = new ProtocolException("unexpected end of stream");
        responseBodyComplete();
        throw e;
      }

      bytesRemaining -= read;
      if (bytesRemaining == 0) {
        responseBodyComplete();
      }
      return read;
    }

    @Override public void close() throws IOException {
      if (closed) return;

      if (bytesRemaining != 0 && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
        realConnection.noNewExchanges(); // Unread bytes remain on the stream.
        responseBodyComplete();
      }

      closed = true;
    }
  }

  /** An HTTP body with alternating chunk sizes and chunk bodies. */
  private class ChunkedSource extends AbstractSource {
    private static final long NO_CHUNK_YET = -1L;
    private final HttpUrl url;
    private long bytesRemainingInChunk = NO_CHUNK_YET;
    private boolean hasMoreChunks = true;

    ChunkedSource(HttpUrl url) {
      this.url = url;
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (!hasMoreChunks) return -1;

      if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize();
        if (!hasMoreChunks) return -1;
      }

      long read = super.read(sink, Math.min(byteCount, bytesRemainingInChunk));
      if (read == -1) {
        realConnection.noNewExchanges(); // The server didn't supply the promised chunk length.
        ProtocolException e = new ProtocolException("unexpected end of stream");
        responseBodyComplete();
        throw e;
      }
      bytesRemainingInChunk -= read;
      return read;
    }

    private void readChunkSize() throws IOException {
      // Read the suffix of the previous chunk.
      if (bytesRemainingInChunk != NO_CHUNK_YET) {
        source.readUtf8LineStrict();
      }
      try {
        bytesRemainingInChunk = source.readHexadecimalUnsignedLong();
        String extensions = source.readUtf8LineStrict().trim();
        if (bytesRemainingInChunk < 0 || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
          throw new ProtocolException("expected chunk size and optional extensions but was \""
              + bytesRemainingInChunk + extensions + "\"");
        }
      } catch (NumberFormatException e) {
        throw new ProtocolException(e.getMessage());
      }
      if (bytesRemainingInChunk == 0L) {
        hasMoreChunks = false;
        trailers = readHeaders();
        HttpHeaders.receiveHeaders(client.cookieJar(), url, trailers);
        responseBodyComplete();
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      if (hasMoreChunks && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
        realConnection.noNewExchanges(); // Unread bytes remain on the stream.
        responseBodyComplete();
      }
      closed = true;
    }
  }

  /** An HTTP message body terminated by the end of the underlying stream. */
  private class UnknownLengthSource extends AbstractSource {
    private boolean inputExhausted;

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = super.read(sink, byteCount);
      if (read == -1) {
        inputExhausted = true;
        responseBodyComplete();
        return -1;
      }
      return read;
    }

    @Override public void close() throws IOException {
      if (closed) return;
      if (!inputExhausted) {
        responseBodyComplete();
      }
      closed = true;
    }
  }
}
