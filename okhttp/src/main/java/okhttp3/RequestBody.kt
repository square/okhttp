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
package okhttp3;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import javax.annotation.Nullable;
import okhttp3.internal.Util;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;
import okio.Source;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class RequestBody {
  /** Returns the Content-Type header for this body. */
  public abstract @Nullable MediaType contentType();

  /**
   * Returns the number of bytes that will be written to {@code sink} in a call to {@link #writeTo},
   * or -1 if that count is unknown.
   */
  public long contentLength() throws IOException {
    return -1;
  }

  /** Writes the content of this request to {@code sink}. */
  public abstract void writeTo(BufferedSink sink) throws IOException;

  /**
   * A duplex request body is special in how it is <strong>transmitted</strong> on the network and
   * in the <strong>API contract</strong> between OkHttp and the application.
   *
   * <p>This method returns false unless it is overridden by a subclass.
   *
   * <h3>Duplex Transmission</h3>
   *
   * <p>With regular HTTP calls the request always completes sending before the response may begin
   * receiving. With duplex the request and response may be interleaved! That is, request body bytes
   * may be sent after response headers or body bytes have been received.
   *
   * <p>Though any call may be initiated as a duplex call, only web servers that are specially
   * designed for this nonstandard interaction will use it. As of 2019-01, the only widely-used
   * implementation of this pattern is <a
   * href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC</a>.
   *
   * <p>Because the encoding of interleaved data is not well-defined for HTTP/1, duplex request
   * bodies may only be used with HTTP/2. Calls to HTTP/1 servers will fail before the HTTP request
   * is transmitted. If you cannot ensure that your client and server both support HTTP/2, do not
   * use this feature.
   *
   * <p>Duplex APIs</p>
   *
   * <p>With regular request bodies it is not legal to write bytes to the sink passed to {@link
   * RequestBody#writeTo} after that method returns. For duplex requests bodies that condition is
   * lifted. Such writes occur on an application-provided thread and may occur concurrently with
   * reads of the {@link ResponseBody}. For duplex request bodies, {@link #writeTo} should return
   * quickly, possibly by handing off the provided request body to another thread to perform
   * writing.
   */
  public boolean isDuplex() {
    return false;
  }

  /**
   * Returns true if this body expects at most one call to {@link #writeTo} and can be transmitted
   * at most once. This is typically used when writing the request body is destructive and it is not
   * possible to recreate the request body after it has been sent.
   *
   * <p>This method returns false unless it is overridden by a subclass.
   *
   * <p>By default OkHttp will attempt to retransmit request bodies when the original request fails
   * due to a stale connection, a client timeout (HTTP 408), a satisfied authorization challenge
   * (HTTP 401 and 407), or a retryable server failure (HTTP 503 with a {@code Retry-After: 0}
   * header).
   */
  public boolean isOneShot() {
    return false;
  }

  /**
   * Returns a new request body that transmits {@code content}. If {@code contentType} is non-null
   * and lacks a charset, this will use UTF-8.
   */
  public static RequestBody create(@Nullable MediaType contentType, String content) {
    Charset charset = UTF_8;
    if (contentType != null) {
      charset = contentType.charset();
      if (charset == null) {
        charset = UTF_8;
        contentType = MediaType.parse(contentType + "; charset=utf-8");
      }
    }
    byte[] bytes = content.getBytes(charset);
    return create(contentType, bytes);
  }

  /** Returns a new request body that transmits {@code content}. */
  public static RequestBody create(
      final @Nullable MediaType contentType, final ByteString content) {
    return new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() throws IOException {
        return content.size();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(content);
      }
    };
  }

  /** Returns a new request body that transmits {@code content}. */
  public static RequestBody create(final @Nullable MediaType contentType, final byte[] content) {
    return create(contentType, content, 0, content.length);
  }

  /** Returns a new request body that transmits {@code content}. */
  public static RequestBody create(final @Nullable MediaType contentType, final byte[] content,
      final int offset, final int byteCount) {
    if (content == null) throw new NullPointerException("content == null");
    Util.checkOffsetAndCount(content.length, offset, byteCount);
    return new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return byteCount;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(content, offset, byteCount);
      }
    };
  }

  /** Returns a new request body that transmits the content of {@code file}. */
  public static RequestBody create(final @Nullable MediaType contentType, final File file) {
    if (file == null) throw new NullPointerException("file == null");

    return new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return file.length();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        try (Source source = Okio.source(file)) {
          sink.writeAll(source);
        }
      }
    };
  }
}
