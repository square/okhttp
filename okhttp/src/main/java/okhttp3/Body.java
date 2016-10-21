/*
 * Copyright (C) 2016 Square, Inc.
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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Arrays;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;

import static okhttp3.internal.Util.UTF_8;

public abstract class Body implements Closeable {
  public static final Body EMPTY = create(null, Util.EMPTY_BYTE_ARRAY);

  private Reader charStreamReader;

  @Override public void close() throws IOException {
    Util.closeQuietly(source());
  }

  /**
   * Returns the {@link MediaType} for this body.
   */
  public MediaType contentType() {
    return null;
  }

  /**
   * Returns the content length or -1 when the content length is unknown.
   */
  public long contentLength() throws IOException {
    return -1L;
  }

  /**
   * Returns a {@link BufferedSource} to read the contents of this {@link Body}.
   * throws {@link UnsupportedOperationException} if this body is not readable.
   */
  public BufferedSource source() {
    throw new UnsupportedOperationException("Cannot read a non-readable body");
  }

  /**
   * Implementations of this method should write the contents of this body to the
   * given {@link BufferedSink}.
   * throws {@link UnsupportedOperationException} if this body is not writable.
   */
  public void writeTo(BufferedSink sink) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the body as a byte array.
   *
   * <p>This method loads entire body into memory. If the body is very large this may trigger
   * an {@link OutOfMemoryError}. Prefer to stream the body if this is a possibility
   * for your request or response.
   */
  public final byte[] bytes() throws IOException {
    long contentLength = contentLength();
    if (contentLength > Integer.MAX_VALUE) {
      throw new IOException("Cannot buffer entire body for content length: " + contentLength);
    }

    byte[] bytes;
    try {
      bytes = source().readByteArray();
    } finally {
      close();
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
   * Peeks up to {@code byteCount} bytes from this body and returns them as a new body.
   * If fewer than {@code byteCount} bytes are in the body, the full body is returned.
   * If more than {@code byteCount} bytes are in the body, the returned body will be truncated
   * to {@code byteCount} bytes.
   *
   * <p>It is an error to call this method after the body has been consumed.
   *
   * <p><strong>Warning:</strong> this method loads the requested bytes into memory. Most
   * applications should set a modest limit on {@code byteCount}, such as 1 MiB.
   */
  public final Body peek(long byteCount) throws IOException {
    BufferedSource source = source();
    source.request(byteCount);
    Buffer copy = source.buffer().clone();

    // There may be more than byteCount bytes in source.buffer(). If there is, return a prefix.
    final Buffer result;
    if (copy.size() > byteCount) {
      result = new Buffer();
      result.write(copy, byteCount);
      copy.clear();
    } else {
      result = copy;
    }

    final MediaType mediaType = this.contentType();
    return new ReadableBody() {
      @Override public MediaType contentType() {
        return mediaType;
      }

      @Override public long contentLength() {
        return result.size();
      }

      @Override public okio.BufferedSource source() {
        return result;
      }
    };
  }

  public final InputStream byteStream() {
    return source().inputStream();
  }

  /**
   * Returns this body as a character stream decoded with the charset of the {@code contentType}.
   * If it lacks a charset, this will attempt to decode the response body in accordance to
   * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">its BOM</a> or UTF-8.
   */
  public final Reader charStream() {
    Reader r = charStreamReader;
    return r != null ? r : (charStreamReader = new BomAwareReader(source(), charset()));
  }

  /**
   * Returns the body as a string decoded with the charset of the {@code contentType}. If that
   * lacks a charset, this will attempt to decode the body in accordance to
   * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">its BOM</a> or UTF-8.
   * Closes {@link ReadableBody} automatically.
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
      Util.closeQuietly(source());
    }
  }

  private Charset charset() {
    MediaType contentType = contentType();
    return contentType != null ? contentType.charset(UTF_8) : UTF_8;
  }

  /** Creates a new body that can be used for reading or writing. */
  public static Body create(MediaType contentType, byte[] content) {
    if (content == null) throw new NullPointerException("content is null");
    return new StaticBody(contentType, content.length, content);
  }

  /**
   * Returns a new body that transmits {@code content}. If {@code contentType} lacks a
   * charset, this will use UTF-8.
   */
  public static Body create(MediaType contentType, String content) {
    if (content == null) throw new NullPointerException("content is null");
    Charset charset = UTF_8;
    if (contentType != null) {
      charset = contentType.charset();
      if (charset == null) {
        charset = UTF_8;
        contentType = MediaType.parse(contentType + "; charset=utf-8");
      }
    }
    byte[] bytes = new Buffer().writeString(content, charset).readByteArray();
    return Body.create(contentType, bytes);
  }

  /** Returns a new body that transmits {@code content}. */
  static Body create(final MediaType contentType, final long contentLength,
      final BufferedSource content) {
    return new ReadableBody() {
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

  /** Returns a new body that transmits {@code content}. */
  public static Body create(MediaType contentType, ByteString content) {
    return Body.create(contentType, content.size(), new Buffer().write(content));
  }

  /** Returns a new body that transmits {@code content}. */
  public static Body create(MediaType contentType, byte[] content, int offset, int byteCount) {
    Util.checkOffsetAndCount(content.length, offset, byteCount);
    return Body.create(contentType, Arrays.copyOfRange(content, offset, offset + byteCount));
  }

  /** Returns a new body that transmits the contents of {@code file}. */
  public static Body create(final MediaType contentType, final File file)
      throws FileNotFoundException {
    return new WritableBody() {
      @Override public MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return file.length();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        Source source = null;
        try {
          source = Okio.source(file);
          sink.writeAll(source);
        } finally {
          Util.closeQuietly(source);
        }
      }
    };
  }

  /**
   * Returns a new body that transmits the contents of {@code source} using {@code headers}
   * to find content type and length.
   */
  public static Body create(final Headers headers, final BufferedSource source) {
    return new ReadableBody() {
      @Override public MediaType contentType() {
        String contentTypeHeader = headers.get("Content-Type");
        return contentTypeHeader != null ? MediaType.parse(contentTypeHeader) : null;
      }

      @Override public long contentLength() {
        return HttpHeaders.contentLength(headers);
      }

      @Override public BufferedSource source() {
        return source;
      }
    };
  }
}
