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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public abstract class RequestBody {
  /** Returns the Content-Type header for this body. */
  public abstract MediaType contentType();

  /**
   * Returns the number of bytes that will be written to {@code out} in a call
   * to {@link #writeTo}, or -1 if that count is unknown.
   */
  public long contentLength() {
    return -1;
  }

  /** Writes the content of this request to {@code out}. */
  public abstract void writeTo(BufferedSink sink) throws IOException;

  /**
   * Returns a new request body that transmits {@code content}. If {@code
   * contentType} lacks a charset, this will use UTF-8.
   */
  public static RequestBody create(MediaType contentType, String content) {
    contentType = contentType.charset() != null
        ? contentType
        : MediaType.parse(contentType + "; charset=utf-8");
    try {
      byte[] bytes = content.getBytes(contentType.charset().name());
      return create(contentType, bytes);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError();
    }
  }

  /** Returns a new request body that transmits {@code content}. */
  public static RequestBody create(final MediaType contentType, final byte[] content) {
    if (contentType == null) throw new NullPointerException("contentType == null");
    if (content == null) throw new NullPointerException("content == null");

    return new RequestBody() {
      @Override public MediaType contentType() {
        return contentType;
      }

      @Override public long contentLength() {
        return content.length;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(content);
      }
    };
  }

  /** Returns a new request body that transmits the content of {@code file}. */
  public static RequestBody create(final MediaType contentType, final File file) {
    if (contentType == null) throw new NullPointerException("contentType == null");
    if (file == null) throw new NullPointerException("content == null");

    return new RequestBody() {
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
}
