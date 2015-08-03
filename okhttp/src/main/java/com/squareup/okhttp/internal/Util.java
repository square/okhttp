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

import com.squareup.okhttp.HttpUrl;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.ByteString;
import okio.Source;

/** Junk drawer of utility methods. */
public final class Util {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Util() {
  }

  public static void checkOffsetAndCount(long arrayLength, long offset, long count) {
    if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw new ArrayIndexOutOfBoundsException();
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
   * Attempts to exhaust {@code source}, returning true if successful. This is useful when reading
   * a complete source is helpful, such as when doing so completes a cache body or frees a socket
   * connection for reuse.
   */
  public static boolean discard(Source source, int timeout, TimeUnit timeUnit) {
    try {
      return skipAll(source, timeout, timeUnit);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Reads until {@code in} is exhausted or the deadline has been reached. This is careful to not
   * extend the deadline if one exists already.
   */
  public static boolean skipAll(Source source, int duration, TimeUnit timeUnit) throws IOException {
    long now = System.nanoTime();
    long originalDuration = source.timeout().hasDeadline()
        ? source.timeout().deadlineNanoTime() - now
        : Long.MAX_VALUE;
    source.timeout().deadlineNanoTime(now + Math.min(originalDuration, timeUnit.toNanos(duration)));
    try {
      Buffer skipBuffer = new Buffer();
      while (source.read(skipBuffer, 2048) != -1) {
        skipBuffer.clear();
      }
      return true; // Success! The source has been exhausted.
    } catch (InterruptedIOException e) {
      return false; // We ran out of time before exhausting the source.
    } finally {
      if (originalDuration == Long.MAX_VALUE) {
        source.timeout().clearDeadline();
      } else {
        source.timeout().deadlineNanoTime(now + originalDuration);
      }
    }
  }

  /** Returns a 32 character string containing an MD5 hash of {@code s}. */
  public static String md5Hex(String s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("MD5");
      byte[] md5bytes = messageDigest.digest(s.getBytes("UTF-8"));
      return ByteString.of(md5bytes).hex();
    } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  /** Returns a Base 64-encoded string containing a SHA-1 hash of {@code s}. */
  public static String shaBase64(String s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
      byte[] sha1Bytes = messageDigest.digest(s.getBytes("UTF-8"));
      return ByteString.of(sha1Bytes).base64();
    } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  /** Returns a SHA-1 hash of {@code s}. */
  public static ByteString sha1(ByteString s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
      byte[] sha1Bytes = messageDigest.digest(s.toByteArray());
      return ByteString.of(sha1Bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** Returns an immutable copy of {@code list}. */
  public static <T> List<T> immutableList(List<T> list) {
    return Collections.unmodifiableList(new ArrayList<>(list));
  }

  /** Returns an immutable list containing {@code elements}. */
  public static <T> List<T> immutableList(T... elements) {
    return Collections.unmodifiableList(Arrays.asList(elements.clone()));
  }

  /** Returns an immutable copy of {@code map}. */
  public static <K, V> Map<K, V> immutableMap(Map<K, V> map) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  public static ThreadFactory threadFactory(final String name, final boolean daemon) {
    return new ThreadFactory() {
      @Override public Thread newThread(Runnable runnable) {
        Thread result = new Thread(runnable, name);
        result.setDaemon(daemon);
        return result;
      }
    };
  }

  /**
   * Returns an array containing containing only elements found in {@code first}  and also in
   * {@code second}. The returned elements are in the same order as in {@code first}.
   */
  @SuppressWarnings("unchecked")
  public static <T> T[] intersect(Class<T> arrayType, T[] first, T[] second) {
    List<T> result = intersect(first, second);
    return result.toArray((T[]) Array.newInstance(arrayType, result.size()));
  }

  /**
   * Returns a list containing containing only elements found in {@code first}  and also in
   * {@code second}. The returned elements are in the same order as in {@code first}.
   */
  private static <T> List<T> intersect(T[] first, T[] second) {
    List<T> result = new ArrayList<>();
    for (T a : first) {
      for (T b : second) {
        if (a.equals(b)) {
          result.add(b);
          break;
        }
      }
    }
    return result;
  }

  public static String hostHeader(HttpUrl url) {
    // TODO: square braces for IPv6 ?
    return url.port() != HttpUrl.defaultPort(url.scheme())
        ? url.host() + ":" + url.port()
        : url.host();
  }

  /** Returns {@code s} with control characters and non-ASCII characters replaced with '?'. */
  public static String toHumanReadableAscii(String s) {
    for (int i = 0, length = s.length(), c; i < length; i += Character.charCount(c)) {
      c = s.codePointAt(i);
      if (c > '\u001f' && c < '\u007f') continue;

      Buffer buffer = new Buffer();
      buffer.writeUtf8(s, 0, i);
      for (int j = i; j < length; j += Character.charCount(c)) {
        c = s.codePointAt(j);
        buffer.writeUtf8CodePoint(c > '\u001f' && c < '\u007f' ? c : '?');
      }
      return buffer.readUtf8();
    }
    return s;
  }
}
