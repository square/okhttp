/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * An immutable sequence of bytes.
 *
 * <p><strong>Full disclosure:</strong> this class provides untrusted input and
 * output streams with raw access to the underlying byte array. A hostile
 * stream implementation could keep a reference to the mutable byte string,
 * violating the immutable guarantee of this class. For this reason a byte
 * string's immutability guarantee cannot be relied upon for security in applets
 * and other environments that run both trusted and untrusted code in the same
 * process.
 */
public final class ByteString {
  private final byte[] data;
  private transient int hashCode; // Lazily computed; 0 if unknown.
  private transient String utf8; // Lazily computed.

  /**
   * A singleton empty {@code ByteString}.
   */
  public static final ByteString EMPTY = new ByteString(Util.EMPTY_BYTE_ARRAY);

  /**
   * Returns a new byte string containing the bytes of {@code data}, or
   * {@link #EMPTY} if {@code data} is an empty array.
   */
  public static ByteString of(byte... data) {
    return new ByteString(data.clone());
  }

  /**
   * Returns a new byte string containing the bytes of {@code data}
   * from {@code offset} to {@code offset + count - 1}, inclusive, or
   * {@link #EMPTY} if {@code count} is zero.
   */
  public static ByteString of(byte[] data, int offset, int count) {
    byte[] bytes = new byte[count];
    System.arraycopy(data, offset, bytes, 0, count);
    return new ByteString(bytes);
  }

  /**
   * Returns a new byte string containing the {@code UTF-8} bytes of {@code s},
   * or {@link #EMPTY} if {@code s} is zero length.
   */
  public static ByteString encodeUtf8(String s) {
    ByteString byteString = new ByteString(s.getBytes(Util.UTF_8));
    byteString.utf8 = s;
    return byteString;
  }

  /** Constructs a new {@code String} by decoding the bytes as UTF-8. */
  public String utf8() {
    String result = utf8;
    // We don't care if we double-allocate in racy code.
    return result != null ? result : (utf8 = new String(data, Util.UTF_8));
  }

  /**
   * Returns true when {@code s} is not null and its {@code UTF-8} encoded
   * bytes are equivalent to the bytes wrapped by this byte string.
   */
  public boolean utf8Equals(String s) {
    if (s == null) return false;
    // TODO: avoid allocation
    return utf8().equals(s);
  }

  /**
   * Reads {@code count} bytes from {@code in} and returns the result.
   *
   * @throws EOFException if {@code in} has fewer than {@code count} bytes to
   * read.
   */
  public static ByteString read(InputStream in, int count) throws IOException {
    byte[] result = new byte[count];
    for (int c = 0; c < count; ) {
      int read = in.read(result, c, count - c);
      if (read == -1) throw new EOFException("Expected " + count + "; received " + c);
      c += read;
    }
    return new ByteString(result);
  }

  public static ByteString concat(ByteString... byteStrings) {
    int size = 0;
    for (ByteString byteString : byteStrings) {
      size += byteString.size();
    }
    byte[] result = new byte[size];
    int pos = 0;
    for (ByteString byteString : byteStrings) {
      System.arraycopy(byteString.data, 0, result, pos, byteString.size());
      pos += byteString.size();
    }
    return new ByteString(result);
  }

  private ByteString(byte[] data) {
    this.data = data; // Trusted internal constructor doesn't clone data.
  }

  /**
   * Returns the number of bytes in this ByteString.
   */
  public int size() {
    return data.length;
  }

  /**
   * Returns a byte array containing a copy of the bytes in this {@code ByteString}.
   */
  public byte[] toByteArray() {
    return data.clone();
  }

  /** Writes the contents of this byte string to {@code out}. */
  public void write(OutputStream out) throws IOException {
    out.write(data);
  }

  @Override public boolean equals(Object o) {
    return o == this || o instanceof ByteString && Arrays.equals(((ByteString) o).data, data);
  }

  @Override public int hashCode() {
    int result = hashCode;
    return result != 0 ? result : (hashCode = Arrays.hashCode(data));
  }
}
