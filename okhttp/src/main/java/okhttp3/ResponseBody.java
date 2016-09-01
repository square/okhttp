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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSource;

import static okhttp3.internal.Util.UTF_8;

/**
 * A one-shot stream from the origin server to the client application with the raw bytes of the
 * response body. Each response body is supported by an active connection to the webserver. This
 * imposes both obligations and limits on the client application.
 *
 * <h3>The response body must be closed.</h3>
 *
 * Each response body is backed by a limited resource like a socket (live network responses) or
 * an open file (for cached responses). Failing to close the response body will leak resources and
 * may ultimately cause the application to slow down or crash.
 *
 * <p>Both this class and {@link Response} implement {@link Closeable}. Closing a response simply
 * closes its response body. If you invoke {@link Call#execute()} or implement {@link
 * Callback#onResponse} you must close this body by calling any of the following methods:
 *
 * <ul>
 *   <li>Response.close()</li>
 *   <li>Response.body().close()</li>
 *   <li>Response.body().source().close()</li>
 *   <li>Response.body().charStream().close()</li>
 *   <li>Response.body().byteString().close()</li>
 *   <li>Response.body().bytes()</li>
 *   <li>Response.body().string()</li>
 * </ul>
 *
 * <p>There is no benefit to invoking multiple {@code close()} methods for the same response body.
 *
 * <p>For synchronous calls, the easiest way to make sure a response body is closed is with a {@code
 * try} block. With this structure the compiler inserts an implicit {@code finally} clause that
 * calls {@code close()} for you.
 *
 * <pre>   {@code
 *
 *   Call call = client.newCall(request);
 *   try (Response response = call.execute()) {
 *     ... // Use the response.
 *   }
 * }</pre>
 *
 * You can use a similar block for asynchronous calls: <pre>   {@code
 *
 *   Call call = client.newCall(request);
 *   call.enqueue(new Callback() {
 *     public void onResponse(Call call, Response response) throws IOException {
 *       try (ResponseBody responseBody = response.body()) {
 *         ... // Use the response.
 *       }
 *     }
 *
 *     public void onFailure(Call call, IOException e) {
 *       ... // Handle the failure.
 *     }
 *   });
 * }</pre>
 *
 * These examples will not work if you're consuming the response body on another thread. In such
 * cases the consuming thread must call {@link #close} when it has finished reading the response
 * body.
 *
 * <h3>The response body can be consumed only once.</h3>
 *
 * <p>This class may be used to stream very large responses. For example, it is possible to use this
 * class to read a response that is larger than the entire memory allocated to the current process.
 * It can even stream a response larger than the total storage on the current device, which is a
 * common requirement for video streaming applications.
 *
 * <p>Because this class does not buffer the full response in memory, the application may not
 * re-read the bytes of the response. Use this one shot to read the entire response into memory with
 * {@link #bytes()} or {@link #string()}. Or stream the response with either {@link #source()},
 * {@link #byteStream()}, or {@link #charStream()}.
 */
public abstract class ResponseBody implements Closeable {
  /** Multiple calls to {@link #charStream()} must return the same instance. */
  private Reader reader;

  public abstract MediaType contentType();

  /**
   * Returns the number of bytes in that will returned by {@link #bytes}, or {@link #byteStream}, or
   * -1 if unknown.
   */
  public abstract long contentLength();

  public final InputStream byteStream() {
    return source().inputStream();
  }

  public abstract BufferedSource source();

  /**
   * Returns the response as a byte array.
   *
   * <p>This method loads entire response body into memory. If the response body is very large this
   * may trigger an {@link OutOfMemoryError}. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  public final byte[] bytes() throws IOException {
    long contentLength = contentLength();
    if (contentLength > Integer.MAX_VALUE) {
      throw new IOException("Cannot buffer entire body for content length: " + contentLength);
    }

    BufferedSource source = source();
    byte[] bytes;
    try {
      bytes = source.readByteArray();
    } finally {
      Util.closeQuietly(source);
    }
    if (contentLength != -1 && contentLength != bytes.length) {
      throw new IOException("Content-Length ("
          + contentLength
          + ") and stream length ("
          + bytes.length
          + ") disagree");
    }
    return bytes;
  }

  /**
   * Returns the response as a character stream decoded with the charset of the Content-Type header.
   * If that header is either absent or lacks a charset, this will attempt to decode the response
   * body in accordance to <a href="https://en.wikipedia.org/wiki/Byte_order_mark">its BOM</a> or
   * UTF-8.
   */
  public final Reader charStream() {
    Reader r = reader;
    return r != null ? r : (reader = new BomAwareReader(source(), charset()));
  }

  /**
   * Returns the response as a string decoded with the charset of the Content-Type header. If that
   * header is either absent or lacks a charset, this will attempt to decode the response body in
   * accordance to <a href="https://en.wikipedia.org/wiki/Byte_order_mark">its BOM</a> or UTF-8.
   * Closes {@link ResponseBody} automatically.
   *
   * <p>This method loads entire response body into memory. If the response body is very large this
   * may trigger an {@link OutOfMemoryError}. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  public final String string() throws IOException {
    BufferedSource source = source();
    try {
      Charset charset = Util.bomAwareCharset(source, charset());
      return source.readString(charset);
    } finally {
      Util.closeQuietly(source);
    }
  }

  private Charset charset() {
    MediaType contentType = contentType();
    return contentType != null ? contentType.charset(UTF_8) : UTF_8;
  }

  @Override public void close() {
    Util.closeQuietly(source());
  }

  /**
   * Returns a new response body that transmits {@code content}. If {@code contentType} is non-null
   * and lacks a charset, this will use UTF-8.
   */
  public static ResponseBody create(MediaType contentType, String content) {
    Charset charset = UTF_8;
    if (contentType != null) {
      charset = contentType.charset();
      if (charset == null) {
        charset = UTF_8;
        contentType = MediaType.parse(contentType + "; charset=utf-8");
      }
    }
    Buffer buffer = new Buffer().writeString(content, charset);
    return create(contentType, buffer.size(), buffer);
  }

  /** Returns a new response body that transmits {@code content}. */
  public static ResponseBody create(final MediaType contentType, byte[] content) {
    Buffer buffer = new Buffer().write(content);
    return create(contentType, content.length, buffer);
  }

  /** Returns a new response body that transmits {@code content}. */
  public static ResponseBody create(
      final MediaType contentType, final long contentLength, final BufferedSource content) {
    if (content == null) throw new NullPointerException("source == null");
    return new ResponseBody() {
      @Override public MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return contentLength;
      }

      @Override public BufferedSource source() {
        return content;
      }
    };
  }

  static final class BomAwareReader extends Reader {
    private final BufferedSource source;
    private final Charset charset;

    private boolean closed;
    private Reader delegate;

    private BomAwareReader(BufferedSource source, Charset charset) {
      this.source = source;
      this.charset = charset;
    }

    @Override public int read(char[] cbuf, int off, int len) throws IOException {
      if (closed) throw new IOException("Stream closed");

      Reader delegate = this.delegate;
      if (delegate == null) {
        Charset charset = Util.bomAwareCharset(source, this.charset);
        delegate = this.delegate = new InputStreamReader(source.inputStream(), charset);
      }
      return delegate.read(cbuf, off, len);
    }

    @Override public void close() throws IOException {
      closed = true;
      if (delegate != null) {
        delegate.close();
      } else {
        source.close();
      }
    }
  }
}
