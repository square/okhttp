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

import okhttp3.internal.Util;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;

import static okhttp3.internal.Util.UTF_8;

/**
 * The body for HTTP requests or responses.
 */
public class Body implements Closeable {
  public static final byte[] EMPTY_ARRAY = new byte[0];
  public static final Body EMPTY = new Body(null, EMPTY_ARRAY);

  protected final MediaType contentType;
  protected final long contentLength;
  protected final BufferedSource readableContent;
  protected final byte[] writableContent;

  private Reader reader;

  public Body() {
    this(null, -1, null, null);
  }

  public Body(MediaType contentType) {
    this(contentType, -1, null, null);
  }

  public Body(MediaType contentType, byte[] content) {
    this(contentType, content.length, content, new Buffer().write(content));
  }

  public Body(MediaType contentType, Buffer content) {
    this(contentType, content.size(), content.readByteArray(), content);
  }

  public Body(MediaType contentType, BufferedSource content) {
    this(contentType, -1, null, content);
  }

  public Body(MediaType contentType, long contentLength, BufferedSource content) {
    this(contentType, contentLength, null, content);
  }

  public Body(MediaType contentType, long contentLength, byte[] writableContent,
      BufferedSource readableContent) {
    this.contentType = contentType;
    this.contentLength = contentLength;
    this.writableContent = writableContent;
    this.readableContent = readableContent;
  }

  public MediaType contentType() {
    return contentType;
  }

  public void writeTo(BufferedSink sink) throws IOException {
    if (writableContent == null) throw new UnsupportedOperationException("Not writable");
    sink.writeAll(new Buffer().write(writableContent));
  }

  /**
   * Returns the content length or -1 if the content length is unknown.
   */
  public long contentLength() throws IOException {
    return contentLength;
  }

  public BufferedSource source() {
    if (readableContent == null) throw new UnsupportedOperationException("Not readable");
    return readableContent;
  }

  @Override public void close() {
    Util.closeQuietly(source());
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
   * If more than {@code byteCount} bytes are in the body, the returned budy will be truncated
   * to {@code byteCount} bytes.
   *
   * <p>It is an error to call this method after the body has been consumed.
   *
   * <p><strong>Warning:</strong> this method loads the requested bytes into memory. Most
   * applications should set a modest limit on {@code byteCount}, such as 1 MiB.
   */
  public Body peek(long byteCount) throws IOException {
    BufferedSource source = source();
    source.request(byteCount);
    Buffer copy = source.buffer().clone();

    // There may be more than byteCount bytes in source.buffer(). If there is, return a prefix.
    Buffer result;
    if (copy.size() > byteCount) {
      result = new Buffer();
      result.write(copy, byteCount);
      copy.clear();
    } else {
      result = copy;
    }

    return new Body(contentType(), result.size(), result);
  }

  public final InputStream byteStream() {
    return source().inputStream();
  }

  /**
   * Returns the response as a character stream decoded with the charset of the {@code contentType}.
   * If that lacks a charset, this will attempt to decode the response body in accordance to
   * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">its BOM</a> or UTF-8.
   */
  public final Reader charStream() {
    Reader r = reader;
    return r != null ? r : (reader = new BomAwareReader(source(), charset()));
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

  /** Returns a new body that transmits {@code content}. */
  public static Body create(final MediaType contentType, byte[] content) {
    if (content == null) throw new NullPointerException("content is null");
    return new Body(contentType, content);
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
    return new Body(contentType, bytes);
  }

  /** Returns a new body that transmits {@code content}. */
  static Body create(MediaType contentType, long contentLength, BufferedSource content) {
    return new Body(contentType, contentLength, content);
  }

  /** Returns a new body that transmits {@code content}. */
  public static Body create(MediaType contentType, ByteString content) {
    return new Body(contentType, content.size(), new Buffer().write(content));
  }

  /** Returns a new body that transmits {@code content}. */
  public static Body create(MediaType contentType, byte[] content, int offset, int byteCount) {
    Util.checkOffsetAndCount(content.length, offset, byteCount);
    return new Body(contentType, new Buffer().write(content, offset, byteCount));
  }

  /** Returns a new body that transmits the content of {@code file}. */
  public static Body create(MediaType contentType, final File file) throws FileNotFoundException {
    return new Body(contentType, file.length(), null, null) {
      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeAll(Okio.source(file));
      }
    };
  }

  /**
   * Returns a new body that transmits the content of {@code source} using {@code headers}
   * to find content type and length.
   */
  public static Body create(Headers headers, BufferedSource source) {
    String contentTypeHeader = headers.get("Content-Type");
    MediaType contentType = contentTypeHeader != null ? MediaType.parse(contentTypeHeader) : null;
    long contentLength = HttpHeaders.contentLength(headers);
    return new Body(contentType, contentLength, source);
  }
}
