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

package com.squareup.okhttp.internal;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/** Junk drawer of utility methods. */
public final class Util {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  /** A cheap and type-safe constant for the ISO-8859-1 Charset. */
  public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

  /** A cheap and type-safe constant for the US-ASCII Charset. */
  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");
  private static AtomicReference<byte[]> skipBuffer = new AtomicReference<byte[]>();

  private static final char[] DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  private Util() {
  }

  public static int getEffectivePort(URI uri) {
    return getEffectivePort(uri.getScheme(), uri.getPort());
  }

  public static int getEffectivePort(URL url) {
    return getEffectivePort(url.getProtocol(), url.getPort());
  }

  private static int getEffectivePort(String scheme, int specifiedPort) {
    return specifiedPort != -1 ? specifiedPort : getDefaultPort(scheme);
  }

  public static int getDefaultPort(String scheme) {
    if ("http".equalsIgnoreCase(scheme)) {
      return 80;
    } else if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    } else {
      return -1;
    }
  }

  public static void checkOffsetAndCount(int arrayLength, int offset, int count) {
    if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  public static void pokeInt(byte[] dst, int offset, int value, ByteOrder order) {
    if (order == ByteOrder.BIG_ENDIAN) {
      dst[offset++] = (byte) ((value >> 24) & 0xff);
      dst[offset++] = (byte) ((value >> 16) & 0xff);
      dst[offset++] = (byte) ((value >> 8) & 0xff);
      dst[offset] = (byte) ((value >> 0) & 0xff);
    } else {
      dst[offset++] = (byte) ((value >> 0) & 0xff);
      dst[offset++] = (byte) ((value >> 8) & 0xff);
      dst[offset++] = (byte) ((value >> 16) & 0xff);
      dst[offset] = (byte) ((value >> 24) & 0xff);
    }
  }

  /** Returns true if two possibly-null objects are equal. */
  public static boolean equal(Object a, Object b) {
    return a == b || (a != null && a.equals(b));
  }

  /**
   * Closes {@code closeable}, ignoring any checked exceptions. Does nothing
   * if {@code closeable} is null.
   */
  public static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code socket}, ignoring any checked exceptions. Does nothing if
   * {@code socket} is null.
   */
  public static void closeQuietly(Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code serverSocket}, ignoring any checked exceptions. Does nothing if
   * {@code serverSocket} is null.
   */
  public static void closeQuietly(ServerSocket serverSocket) {
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code a} and {@code b}. If either close fails, this completes
   * the other close and rethrows the first encountered exception.
   */
  public static void closeAll(Closeable a, Closeable b) throws IOException {
    Throwable thrown = null;
    try {
      a.close();
    } catch (Throwable e) {
      thrown = e;
    }
    try {
      b.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    if (thrown == null) return;
    if (thrown instanceof IOException) throw (IOException) thrown;
    if (thrown instanceof RuntimeException) throw (RuntimeException) thrown;
    if (thrown instanceof Error) throw (Error) thrown;
    throw new AssertionError(thrown);
  }

  /**
   * Deletes the contents of {@code dir}. Throws an IOException if any file
   * could not be deleted, or if {@code dir} is not a readable directory.
   */
  public static void deleteContents(File dir) throws IOException {
    File[] files = dir.listFiles();
    if (files == null) {
      throw new IOException("not a readable directory: " + dir);
    }
    for (File file : files) {
      if (file.isDirectory()) {
        deleteContents(file);
      }
      if (!file.delete()) {
        throw new IOException("failed to delete file: " + file);
      }
    }
  }

  /**
   * Implements InputStream.read(int) in terms of InputStream.read(byte[], int, int).
   * InputStream assumes that you implement InputStream.read(int) and provides default
   * implementations of the others, but often the opposite is more efficient.
   */
  public static int readSingleByte(InputStream in) throws IOException {
    byte[] buffer = new byte[1];
    int result = in.read(buffer, 0, 1);
    return (result != -1) ? buffer[0] & 0xff : -1;
  }

  /**
   * Implements OutputStream.write(int) in terms of OutputStream.write(byte[], int, int).
   * OutputStream assumes that you implement OutputStream.write(int) and provides default
   * implementations of the others, but often the opposite is more efficient.
   */
  public static void writeSingleByte(OutputStream out, int b) throws IOException {
    byte[] buffer = new byte[1];
    buffer[0] = (byte) (b & 0xff);
    out.write(buffer);
  }

  /**
   * Fills 'dst' with bytes from 'in', throwing EOFException if insufficient bytes are available.
   */
  public static void readFully(InputStream in, byte[] dst) throws IOException {
    readFully(in, dst, 0, dst.length);
  }

  /**
   * Reads exactly 'byteCount' bytes from 'in' (into 'dst' at offset 'offset'), and throws
   * EOFException if insufficient bytes are available.
   *
   * Used to implement {@link java.io.DataInputStream#readFully(byte[], int, int)}.
   */
  public static void readFully(InputStream in, byte[] dst, int offset, int byteCount)
      throws IOException {
    if (byteCount == 0) {
      return;
    }
    if (in == null) {
      throw new NullPointerException("in == null");
    }
    if (dst == null) {
      throw new NullPointerException("dst == null");
    }
    checkOffsetAndCount(dst.length, offset, byteCount);
    while (byteCount > 0) {
      int bytesRead = in.read(dst, offset, byteCount);
      if (bytesRead < 0) {
        throw new EOFException();
      }
      offset += bytesRead;
      byteCount -= bytesRead;
    }
  }

  /** Returns the remainder of 'reader' as a string, closing it when done. */
  public static String readFully(Reader reader) throws IOException {
    try {
      StringWriter writer = new StringWriter();
      char[] buffer = new char[1024];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        writer.write(buffer, 0, count);
      }
      return writer.toString();
    } finally {
      reader.close();
    }
  }

  public static void skipAll(InputStream in) throws IOException {
    do {
      in.skip(Long.MAX_VALUE);
    } while (in.read() != -1);
  }

  /**
   * Call {@code in.read()} repeatedly until either the stream is exhausted or
   * {@code byteCount} bytes have been read.
   *
   * <p>This method reuses the skip buffer but is careful to never use it at
   * the same time that another stream is using it. Otherwise streams that use
   * the caller's buffer for consistency checks like CRC could be clobbered by
   * other threads. A thread-local buffer is also insufficient because some
   * streams may call other streams in their skip() method, also clobbering the
   * buffer.
   */
  public static long skipByReading(InputStream in, long byteCount) throws IOException {
    if (byteCount == 0) return 0L;

    // acquire the shared skip buffer.
    byte[] buffer = skipBuffer.getAndSet(null);
    if (buffer == null) {
      buffer = new byte[4096];
    }

    long skipped = 0;
    while (skipped < byteCount) {
      int toRead = (int) Math.min(byteCount - skipped, buffer.length);
      int read = in.read(buffer, 0, toRead);
      if (read == -1) {
        break;
      }
      skipped += read;
      if (read < toRead) {
        break;
      }
    }

    // release the shared skip buffer.
    skipBuffer.set(buffer);

    return skipped;
  }

  /**
   * Copies all of the bytes from {@code in} to {@code out}. Neither stream is closed.
   * Returns the total number of bytes transferred.
   */
  public static int copy(InputStream in, OutputStream out) throws IOException {
    int total = 0;
    byte[] buffer = new byte[8192];
    int c;
    while ((c = in.read(buffer)) != -1) {
      total += c;
      out.write(buffer, 0, c);
    }
    return total;
  }

  /**
   * Returns the ASCII characters up to but not including the next "\r\n", or
   * "\n".
   *
   * @throws java.io.EOFException if the stream is exhausted before the next newline
   * character.
   */
  public static String readAsciiLine(InputStream in) throws IOException {
    // TODO: support UTF-8 here instead
    StringBuilder result = new StringBuilder(80);
    while (true) {
      int c = in.read();
      if (c == -1) {
        throw new EOFException();
      } else if (c == '\n') {
        break;
      }

      result.append((char) c);
    }
    int length = result.length();
    if (length > 0 && result.charAt(length - 1) == '\r') {
      result.setLength(length - 1);
    }
    return result.toString();
  }

  /** Returns a 32 character string containing a hash of {@code s}. */
  public static String hash(String s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("MD5");
      byte[] md5bytes = messageDigest.digest(s.getBytes("UTF-8"));
      return bytesToHexString(md5bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private static String bytesToHexString(byte[] bytes) {
    char[] digits = DIGITS;
    char[] buf = new char[bytes.length * 2];
    int c = 0;
    for (byte b : bytes) {
      buf[c++] = digits[(b >> 4) & 0xf];
      buf[c++] = digits[b & 0xf];
    }
    return new String(buf);
  }

  /** Returns an immutable copy of {@code list}. */
  public static <T> List<T> immutableList(List<T> list) {
    return Collections.unmodifiableList(new ArrayList<T>(list));
  }

  public static ThreadFactory daemonThreadFactory(final String name) {
    return new ThreadFactory() {
      @Override public Thread newThread(Runnable runnable) {
        Thread result = new Thread(runnable, name);
        result.setDaemon(true);
        return result;
      }
    };
  }
}
