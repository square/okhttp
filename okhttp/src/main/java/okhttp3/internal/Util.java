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
package okhttp3.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.IDN;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Source;

/** Junk drawer of utility methods. */
public final class Util {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  public static final ResponseBody EMPTY_RESPONSE = ResponseBody.create(null, EMPTY_BYTE_ARRAY);
  public static final RequestBody EMPTY_REQUEST = RequestBody.create(null, EMPTY_BYTE_ARRAY);

  private static final ByteString UTF_8_BOM = ByteString.decodeHex("efbbff");
  private static final ByteString UTF_16_BE_BOM = ByteString.decodeHex("feff");
  private static final ByteString UTF_16_LE_BOM = ByteString.decodeHex("fffe");
  private static final ByteString UTF_32_BE_BOM = ByteString.decodeHex("0000ffff");
  private static final ByteString UTF_32_LE_BOM = ByteString.decodeHex("ffff0000");

  public static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final Charset UTF_16_BE = Charset.forName("UTF-16BE");
  private static final Charset UTF_16_LE = Charset.forName("UTF-16LE");
  private static final Charset UTF_32_BE = Charset.forName("UTF-32BE");
  private static final Charset UTF_32_LE = Charset.forName("UTF-32LE");

  /** GMT and UTC are equivalent for our purposes. */
  public static final TimeZone UTC = TimeZone.getTimeZone("GMT");

  /**
   * Quick and dirty pattern to differentiate IP addresses from hostnames. This is an approximation
   * of Android's private InetAddress#isNumeric API.
   *
   * <p>This matches IPv6 addresses as a hex string containing at least one colon, and possibly
   * including dots after the first colon. It matches IPv4 addresses as strings containing only
   * decimal digits and dots. This pattern matches strings like "a:.23" and "54" that are neither IP
   * addresses nor hostnames; they will be verified as IP addresses (which is a more strict
   * verification).
   */
  private static final Pattern VERIFY_AS_IP_ADDRESS = Pattern.compile(
      "([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)");

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
   * Closes {@code closeable}, ignoring any checked exceptions. Does nothing if {@code closeable} is
   * null.
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
   * Closes {@code socket}, ignoring any checked exceptions. Does nothing if {@code socket} is
   * null.
   */
  public static void closeQuietly(Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (AssertionError e) {
        if (!isAndroidGetsocknameError(e)) throw e;
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code serverSocket}, ignoring any checked exceptions. Does nothing if {@code
   * serverSocket} is null.
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
   * Attempts to exhaust {@code source}, returning true if successful. This is useful when reading a
   * complete source is helpful, such as when doing so completes a cache body or frees a socket
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
      while (source.read(skipBuffer, 8192) != -1) {
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

  /** Returns a SHA-256 hash of {@code s}. */
  public static ByteString sha256(ByteString s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
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
   * Returns an array containing containing only elements found in {@code first}  and also in {@code
   * second}. The returned elements are in the same order as in {@code first}.
   */
  @SuppressWarnings("unchecked")
  public static <T> T[] intersect(Class<T> arrayType, T[] first, T[] second) {
    List<T> result = intersect(first, second);
    return result.toArray((T[]) Array.newInstance(arrayType, result.size()));
  }

  /**
   * Returns a list containing containing only elements found in {@code first}  and also in {@code
   * second}. The returned elements are in the same order as in {@code first}.
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

  public static String hostHeader(HttpUrl url, boolean includeDefaultPort) {
    String host = url.host().contains(":")
        ? "[" + url.host() + "]"
        : url.host();
    return includeDefaultPort || url.port() != HttpUrl.defaultPort(url.scheme())
        ? host + ":" + url.port()
        : host;
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

  /**
   * Returns true if {@code e} is due to a firmware bug fixed after Android 4.2.2.
   * https://code.google.com/p/android/issues/detail?id=54072
   */
  public static boolean isAndroidGetsocknameError(AssertionError e) {
    return e.getCause() != null && e.getMessage() != null
        && e.getMessage().contains("getsockname failed");
  }

  public static <T> int indexOf(T[] array, T value) {
    for (int i = 0, size = array.length; i < size; i++) {
      if (equal(array[i], value)) return i;
    }
    return -1;
  }

  public static String[] concat(String[] array, String value) {
    String[] result = new String[array.length + 1];
    System.arraycopy(array, 0, result, 0, array.length);
    result[result.length - 1] = value;
    return result;
  }

  /**
   * Increments {@code pos} until {@code input[pos]} is not ASCII whitespace. Stops at {@code
   * limit}.
   */
  public static int skipLeadingAsciiWhitespace(String input, int pos, int limit) {
    for (int i = pos; i < limit; i++) {
      switch (input.charAt(i)) {
        case '\t':
        case '\n':
        case '\f':
        case '\r':
        case ' ':
          continue;
        default:
          return i;
      }
    }
    return limit;
  }

  /**
   * Decrements {@code limit} until {@code input[limit - 1]} is not ASCII whitespace. Stops at
   * {@code pos}.
   */
  public static int skipTrailingAsciiWhitespace(String input, int pos, int limit) {
    for (int i = limit - 1; i >= pos; i--) {
      switch (input.charAt(i)) {
        case '\t':
        case '\n':
        case '\f':
        case '\r':
        case ' ':
          continue;
        default:
          return i + 1;
      }
    }
    return pos;
  }

  /** Equivalent to {@code string.substring(pos, limit).trim()}. */
  public static String trimSubstring(String string, int pos, int limit) {
    int start = skipLeadingAsciiWhitespace(string, pos, limit);
    int end = skipTrailingAsciiWhitespace(string, start, limit);
    return string.substring(start, end);
  }

  /**
   * Returns the index of the first character in {@code input} that contains a character in {@code
   * delimiters}. Returns limit if there is no such character.
   */
  public static int delimiterOffset(String input, int pos, int limit, String delimiters) {
    for (int i = pos; i < limit; i++) {
      if (delimiters.indexOf(input.charAt(i)) != -1) return i;
    }
    return limit;
  }

  /**
   * Returns the index of the first character in {@code input} that is {@code delimiter}. Returns
   * limit if there is no such character.
   */
  public static int delimiterOffset(String input, int pos, int limit, char delimiter) {
    for (int i = pos; i < limit; i++) {
      if (input.charAt(i) == delimiter) return i;
    }
    return limit;
  }

  /**
   * Performs IDN ToASCII encoding and canonicalize the result to lowercase. e.g. This converts
   * {@code ☃.net} to {@code xn--n3h.net}, and {@code WwW.GoOgLe.cOm} to {@code www.google.com}.
   * {@code null} will be returned if the input cannot be ToASCII encoded or if the result
   * contains unsupported ASCII characters.
   */
  public static String domainToAscii(String input) {
    try {
      String result = IDN.toASCII(input).toLowerCase(Locale.US);
      if (result.isEmpty()) return null;

      // Confirm that the IDN ToASCII result doesn't contain any illegal characters.
      if (containsInvalidHostnameAsciiCodes(result)) {
        return null;
      }
      // TODO: implement all label limits.
      return result;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean containsInvalidHostnameAsciiCodes(String hostnameAscii) {
    for (int i = 0; i < hostnameAscii.length(); i++) {
      char c = hostnameAscii.charAt(i);
      // The WHATWG Host parsing rules accepts some character codes which are invalid by
      // definition for OkHttp's host header checks (and the WHATWG Host syntax definition). Here
      // we rule out characters that would cause problems in host headers.
      if (c <= '\u001f' || c >= '\u007f') {
        return true;
      }
      // Check for the characters mentioned in the WHATWG Host parsing spec:
      // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
      // (excluding the characters covered above).
      if (" #%/:?@[\\]".indexOf(c) != -1) {
        return true;
      }
    }
    return false;
  }

  /** Returns true if {@code host} is not a host name and might be an IP address. */
  public static boolean verifyAsIpAddress(String host) {
    return VERIFY_AS_IP_ADDRESS.matcher(host).matches();
  }

  /** Returns a {@link Locale#US} formatted {@link String}. */
  public static String format(String format, Object... args) {
    return String.format(Locale.US, format, args);
  }

  public static Charset bomAwareCharset(BufferedSource source, Charset charset) throws IOException {
    if (source.rangeEquals(0, UTF_8_BOM)) {
      source.skip(UTF_8_BOM.size());
      return UTF_8;
    }
    if (source.rangeEquals(0, UTF_16_BE_BOM)) {
      source.skip(UTF_16_BE_BOM.size());
      return UTF_16_BE;
    }
    if (source.rangeEquals(0, UTF_16_LE_BOM)) {
      source.skip(UTF_16_LE_BOM.size());
      return UTF_16_LE;
    }
    if (source.rangeEquals(0, UTF_32_BE_BOM)) {
      source.skip(UTF_32_BE_BOM.size());
      return UTF_32_BE;
    }
    if (source.rangeEquals(0, UTF_32_LE_BOM)) {
      source.skip(UTF_32_LE_BOM.size());
      return UTF_32_LE;
    }
    return charset;
  }

  /** Re-throws {@code t} if it is a fatal exception which should not be handled. */
  public static void throwIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof ThreadDeath) throw (ThreadDeath) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }
}
