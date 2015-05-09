/*
 * Copyright (C) 2015 Square, Inc.
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
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okio.Buffer;

/**
 * A <a href="https://url.spec.whatwg.org/">URL</a> with an {@code http} or {@code https} scheme.
 *
 * TODO: discussion on canonicalization
 *
 * TODO: discussion on encoding-by-parts
 *
 * TODO: discussion on this vs. java.net.URL vs. java.net.URI
 */
public final class HttpUrl {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

  /** Either "http" or "https". */
  private final String scheme;

  /** Encoded username. */
  private final String username;

  /** Encoded password. */
  private final String password;

  /** Encoded hostname. */
  // TODO(jwilson): implement punycode.
  private final String host;

  /** Either 80, 443 or a user-specified port. In range [1..65535]. */
  private final int port;

  /** Encoded path. */
  private final String path;

  /** Encoded query. */
  private final String query;

  /** Encoded fragment. */
  private final String fragment;

  /** Canonical URL. */
  private final String url;

  private HttpUrl(String scheme, String username, String password, String host, int port,
      String path, String query, String fragment, String url) {
    this.scheme = scheme;
    this.username = username;
    this.password = password;
    this.host = host;
    this.port = port;
    this.path = path;
    this.query = query;
    this.fragment = fragment;
    this.url = url;
  }

  public URL url() {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public URI uri() throws IOException {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  /** Returns either "http" or "https". */
  public String scheme() {
    return scheme;
  }

  public boolean isHttps() {
    return scheme.equals("https");
  }

  public String username() {
    return username;
  }

  public String decodeUsername() {
    return decode(username, 0, username.length());
  }

  /** Returns the encoded password if one is present; null otherwise. */
  public String password() {
    return password;
  }

  /** Returns the decoded password if one is present; null otherwise. */
  public String decodePassword() {
    return password != null ? decode(password, 0, password.length()) : null;
  }

  /**
   * Returns the host address suitable for use with {@link InetAddress#getAllByName(String)}. May
   * be:
   * <ul>
   *   <li>A regular host name, like {@code android.com}.
   *   <li>An IPv4 address, like {@code 127.0.0.1}.
   *   <li>An IPv6 address, like {@code ::1}. Note that there are no square braces.
   *   <li>An encoded IDN, like {@code xn--n3h.net}.
   * </ul>
   */
  public String host() {
    return host;
  }

  /**
   * Returns the decoded (potentially non-ASCII) hostname. The returned string may contain non-ASCII
   * characters and is <strong>not suitable</strong> for DNS lookups; for that use {@link
   * #host}. For example, this may return {@code ☃.net} which is a user-displayable IDN that cannot
   * be used for DNS lookups without encoding.
   */
  public String decodeHost() {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  /**
   * Returns the explicitly-specified port if one was provided, or the default port for this URL's
   * scheme. For example, this returns 8443 for {@code https://square.com:8443/} and 443 for {@code
   * https://square.com/}. The result is in {@code [1..65535]}.
   */
  public int port() {
    return port;
  }

  /**
   * Returns 80 if {@code scheme.equals("http")}, 443 if {@code scheme.equals("https")} and -1
   * otherwise.
   */
  public static int defaultPort(String scheme) {
    if (scheme.equals("http")) {
      return 80;
    } else if (scheme.equals("https")) {
      return 443;
    } else {
      return -1;
    }
  }

  /**
   * Returns the entire path of this URL, encoded for use in HTTP resource resolution. The
   * returned path is always nonempty and is prefixed with {@code /}.
   */
  public String path() {
    return path;
  }

  public List<String> decodePathSegments() {
    List<String> result = new ArrayList<>();
    int segmentStart = 1; // Path always starts with '/'.
    for (int i = segmentStart; i < path.length(); i++) {
      if (path.charAt(i) == '/') {
        result.add(decode(path, segmentStart, i));
        segmentStart = i + 1;
      }
    }
    result.add(decode(path, segmentStart, path.length()));
    return Util.immutableList(result);
  }

  /**
   * Returns the query of this URL, encoded for use in HTTP resource resolution. The returned string
   * may be null (for URLs with no query), empty (for URLs with an empty query) or non-empty (all
   * other URLs).
   */
  public String query() {
    return query;
  }

  /**
   * Returns the first query parameter named {@code name} decoded using UTF-8, or null if there is
   * no such query parameter.
   */
  public String queryParameter(String name) {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public Set<String> queryParameterNames() {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public List<String> queryParameterValues(String name) {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public String queryParameterName(int index) {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public String queryParameterValue(int index) {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public String fragment() {
    return fragment;
  }

  /**
   * Returns the URL that would be retrieved by following {@code link} from this URL.
   *
   * TODO: explain better.
   */
  public HttpUrl resolve(String link) {
    return new Builder().parse(this, link);
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Returns a new {@code OkUrl} representing {@code url} if it is a well-formed HTTP or HTTPS URL,
   * or null if it isn't.
   */
  public static HttpUrl parse(String url) {
    return new Builder().parse(null, url);
  }

  public static HttpUrl get(URL url) {
    return parse(url.toString());
  }

  public static HttpUrl get(URI uri) {
    return parse(uri.toString());
  }

  @Override public boolean equals(Object o) {
    return o instanceof HttpUrl && ((HttpUrl) o).url.equals(url);
  }

  @Override public int hashCode() {
    return url.hashCode();
  }

  @Override public String toString() {
    return url;
  }

  public static final class Builder {
    String scheme;
    String username = "";
    String password;
    String host;
    int port = -1;
    StringBuilder pathBuilder = new StringBuilder();
    String query;
    String fragment;

    public Builder() {
    }

    private Builder(HttpUrl url) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder scheme(String scheme) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder user(String user) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder encodedUser(String encodedUser) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder password(String password) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder encodedPassword(String encodedPassword) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     *     address.
     */
    public Builder host(String host) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder port(int port) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder addPathSegment(String pathSegment) {
      if (pathSegment == null) throw new IllegalArgumentException("pathSegment == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder addEncodedPathSegment(String encodedPathSegment) {
      if (encodedPathSegment == null) {
        throw new IllegalArgumentException("encodedPathSegment == null");
      }
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder encodedPath(String encodedPath) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder encodedQuery(String encodedQuery) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    public Builder addQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (value == null) throw new IllegalArgumentException("value == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    public Builder addEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedValue == null) throw new IllegalArgumentException("encodedValue == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder setQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (value == null) throw new IllegalArgumentException("value == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder setEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedValue == null) throw new IllegalArgumentException("encodedValue == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder removeAllQueryParameters(String name) {
      if (name == null) throw new IllegalArgumentException("name == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder removeAllEncodedQueryParameters(String encodedName) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder fragment(String fragment) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public HttpUrl build() {
      StringBuilder url = new StringBuilder();
      url.append(scheme);
      url.append("://");

      String effectivePassword = (password != null && !password.isEmpty()) ? password : null;
      if (!username.isEmpty() || effectivePassword != null) {
        url.append(username);
        if (effectivePassword != null) {
          url.append(':');
          url.append(effectivePassword);
        }
        url.append('@');
      }

      if (host.indexOf(':') != -1) {
        // Host is an IPv6 address.
        url.append('[');
        url.append(host);
        url.append(']');
      } else {
        url.append(host);
      }

      int defaultPort = defaultPort(scheme);
      int effectivePort = port != -1 ? port : defaultPort;
      if (effectivePort != defaultPort) {
        url.append(':');
        url.append(port);
      }

      String effectivePath = pathBuilder.length() > 0
          ? pathBuilder.toString()
          : "/";
      url.append(effectivePath);

      if (query != null) {
        url.append('?');
        url.append(query);
      }

      if (fragment != null) {
        url.append('#');
        url.append(fragment);
      }

      return new HttpUrl(scheme, username, effectivePassword, host, effectivePort, effectivePath,
          query, fragment, url.toString());
    }

    HttpUrl parse(HttpUrl base, String input) {
      int pos = skipLeadingAsciiWhitespace(input, 0, input.length());
      int limit = skipTrailingAsciiWhitespace(input, pos, input.length());

      // Scheme.
      int schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit);
      if (schemeDelimiterOffset != -1) {
        if (input.regionMatches(true, pos, "https:", 0, 6)) {
          this.scheme = "https";
          pos += "https:".length();
        } else if (input.regionMatches(true, pos, "http:", 0, 5)) {
          this.scheme = "http";
          pos += "http:".length();
        } else {
          return null; // Not an HTTP scheme.
        }
      } else if (base != null) {
        this.scheme = base.scheme;
      } else {
        return null; // No scheme.
      }

      // Authority.
      boolean hasUsername = false;
      int slashCount = slashCount(input, pos, limit);
      if (slashCount >= 2 || base == null || !base.scheme.equals(this.scheme)) {
        // Read an authority if either:
        //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
        //  * The input scheme exists and is different from the base URL's scheme.
        //
        // The structure of an authority is:
        //   username:password@host:port
        //
        // Username, password and port are optional.
        //   [username[:password]@]host[:port]
        pos += slashCount;
        authority:
        while (true) {
          int componentDelimiterOffset = delimiterOffset(input, pos, limit, "@/\\?#");
          int c = componentDelimiterOffset != limit
              ? input.charAt(componentDelimiterOffset)
              : -1;
          switch (c) {
            case '@':
              // User info precedes.
              if (this.password == null) {
                int passwordColonOffset = delimiterOffset(
                    input, pos, componentDelimiterOffset, ":");
                this.username = hasUsername
                    ? (this.username + "%40" + username(input, pos, passwordColonOffset))
                    : username(input, pos, passwordColonOffset);
                if (passwordColonOffset != componentDelimiterOffset) {
                  this.password = password(
                      input, passwordColonOffset + 1, componentDelimiterOffset);
                }
                hasUsername = true;
              } else {
                this.password = this.password + "%40"
                    + password(input, pos, componentDelimiterOffset);
              }
              pos = componentDelimiterOffset + 1;
              break;

            case -1:
            case '/':
            case '\\':
            case '?':
            case '#':
              // Host info precedes.
              int portColonOffset = portColonOffset(input, pos, componentDelimiterOffset);
              if (portColonOffset + 1 < componentDelimiterOffset) {
                this.host = host(input, pos, portColonOffset);
                this.port = port(input, portColonOffset + 1, componentDelimiterOffset);
                if (this.port == -1) return null; // Invalid port.
              } else {
                this.host = host(input, pos, portColonOffset);
                this.port = defaultPort(this.scheme);
              }
              if (this.host == null) return null; // Invalid host.
              pos = componentDelimiterOffset;
              break authority;
          }
        }
      } else {
        // This is a relative link. Copy over all authority components. Also maybe the path & query.
        this.username = base.username;
        this.password = base.password;
        this.host = base.host;
        this.port = base.port;
        int c = pos != limit
            ? input.charAt(pos)
            : -1;
        switch (c) {
          case -1:
          case '#':
            pathBuilder.append(base.path);
            this.query = base.query;
            break;

          case '?':
            pathBuilder.append(base.path);
            break;

          case '/':
          case '\\':
            break;

          default:
            pathBuilder.append(base.path);
            pathBuilder.append('/'); // Because pop wants the input to end with '/'.
            pop();
            break;
        }
      }

      // Resolve the relative path.
      int pathDelimiterOffset = delimiterOffset(input, pos, limit, "?#");
      while (pos < pathDelimiterOffset) {
        int pathSegmentDelimiterOffset = delimiterOffset(input, pos, pathDelimiterOffset, "/\\");
        int segmentLength = pathSegmentDelimiterOffset - pos;

        if ((segmentLength == 2 && input.regionMatches(false, pos, "..", 0, 2))
            || (segmentLength == 4 && input.regionMatches(true, pos, "%2e.", 0, 4))
            || (segmentLength == 4 && input.regionMatches(true, pos, ".%2e", 0, 4))
            || (segmentLength == 6 && input.regionMatches(true, pos, "%2e%2e", 0, 6))) {
          pop();
        } else if ((segmentLength == 1 && input.regionMatches(false, pos, ".", 0, 1))
            || (segmentLength == 3 && input.regionMatches(true, pos, "%2e", 0, 3))) {
          // Skip '.' path segments.
        } else if (pathSegmentDelimiterOffset < pathDelimiterOffset) {
          pathSegment(input, pos, pathSegmentDelimiterOffset);
          pathBuilder.append('/');
        } else {
          pathSegment(input, pos, pathSegmentDelimiterOffset);
        }

        pos = pathSegmentDelimiterOffset;
        if (pathSegmentDelimiterOffset < pathDelimiterOffset) {
          pos++; // Eat '/'.
        }
      }

      // Query.
      if (pos < limit && input.charAt(pos) == '?') {
        int queryDelimiterOffset = delimiterOffset(input, pos, limit, "#");
        this.query = query(input, pos + 1, queryDelimiterOffset);
        pos = queryDelimiterOffset;
      }

      // Fragment.
      if (pos < limit && input.charAt(pos) == '#') {
        this.fragment = fragment(input, pos + 1, limit);
      }

      return build();
    }

    /** Remove the last character '/' of path, plus all characters after the preceding '/'. */
    private void pop() {
      if (pathBuilder.charAt(pathBuilder.length() - 1) != '/') throw new IllegalStateException();

      for (int i = pathBuilder.length() - 2; i >= 0; i--) {
        if (pathBuilder.charAt(i) == '/') {
          pathBuilder.delete(i + 1, pathBuilder.length());
          return;
        }
      }

      // If we get this far, there's nothing to pop. Do nothing.
    }

    /**
     * Increments {@code pos} until {@code input[pos]} is not ASCII whitespace. Stops at {@code
     * limit}.
     */
    private int skipLeadingAsciiWhitespace(String input, int pos, int limit) {
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
    private int skipTrailingAsciiWhitespace(String input, int pos, int limit) {
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

    /**
     * Returns the index of the ':' in {@code input} that is after scheme characters. Returns -1 if
     * {@code input} does not have a scheme that starts at {@code pos}.
     */
    private static int schemeDelimiterOffset(String input, int pos, int limit) {
      if (limit - pos < 2) return -1;

      char c0 = input.charAt(pos);
      if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1; // Not a scheme start char.

      for (int i = pos + 1; i < limit; i++) {
        char c = input.charAt(i);

        if ((c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '+'
            || c == '-'
            || c == '.') {
          continue; // Scheme character. Keep going.
        } else if (c == ':') {
          return i; // Scheme prefix!
        } else {
          return -1; // Non-scheme character before the first ':'.
        }
      }

      return -1; // No ':'; doesn't start with a scheme.
    }

    /** Returns the number of '/' and '\' slashes in {@code input}, starting at {@code pos}. */
    private static int slashCount(String input, int pos, int limit) {
      int slashCount = 0;
      while (pos < limit) {
        char c = input.charAt(pos);
        if (c == '\\' || c == '/') {
          slashCount++;
          pos++;
        } else {
          break;
        }
      }
      return slashCount;
    }

    /**
     * Returns the index of the first character in {@code input} that contains a character in {@code
     * delimiters}. Returns limit if there is no such character.
     */
    private static int delimiterOffset(String input, int pos, int limit, String delimiters) {
      for (int i = pos; i < limit; i++) {
        if (delimiters.indexOf(input.charAt(i)) != -1) return i;
      }
      return limit;
    }

    /** Finds the first ':' in {@code input}, skipping characters between square braces "[...]". */
    private static int portColonOffset(String input, int pos, int limit) {
      for (int i = pos; i < limit; i++) {
        switch (input.charAt(i)) {
          case '[':
            while (++i < limit) {
              if (input.charAt(i) == ']') break;
            }
            break;
          case ':':
            return i;
        }
      }
      return limit; // No colon.
    }

    private String username(String input, int pos, int limit) {
      return encode(input, pos, limit, " \"';<=>@[]^`{}|");
    }

    private String password(String input, int pos, int limit) {
      return encode(input, pos, limit, " \"':;<=>@[]\\^`{}|");
    }

    private static String host(String input, int pos, int limit) {
      // Start by percent decoding the host. The WHATWG spec suggests doing this only after we've
      // checked for IPv6 square braces. But Chrome does it first, and that's more lenient.
      String percentDecoded = decode(input, pos, limit);

      // If the input is encased in square braces "[...]", drop 'em. We have an IPv6 address.
      if (percentDecoded.startsWith("[") && percentDecoded.endsWith("]")) {
        InetAddress inetAddress = decodeIpv6(percentDecoded, 1, percentDecoded.length() - 1);
        return inetAddress != null ? inetAddress.getHostAddress() : null;
      }

      // Do IDN decoding. This converts {@code ☃.net} to {@code xn--n3h.net}.
      String idnDecoded = domainToAscii(percentDecoded);
      if (idnDecoded == null) return null;

      // Confirm that the decoded result doesn't contain any illegal characters.
      int length = idnDecoded.length();
      if (delimiterOffset(idnDecoded, 0, length, "\u0000\t\n\r #%/:?@[\\]") != length) {
        return null;
      }

      return idnDecoded;
    }

    /** Decodes an IPv6 address like 1111:2222:3333:4444:5555:6666:7777:8888 or ::1. */
    private static InetAddress decodeIpv6(String input, int pos, int limit) {
      byte[] address = new byte[16];
      int b = 0;
      int compress = -1;
      int groupOffset = -1;

      for (int i = pos; i < limit; ) {
        if (b == address.length) return null; // Too many groups.

        // Read a delimiter.
        if (i + 2 <= limit && input.regionMatches(i, "::", 0, 2)) {
          // Compression "::" delimiter, which is anywhere in the input, including its prefix.
          if (compress != -1) return null; // Multiple "::" delimiters.
          i += 2;
          b += 2;
          compress = b;
          if (i == limit) break;
        } else if (b != 0) {
          // Group separator ":" delimiter.
          if (input.regionMatches(i, ":", 0, 1)) {
            i++;
          } else if (input.regionMatches(i, ".", 0, 1)) {
            // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
            if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null;
            b += 2; // We rewound two bytes and then added four.
            break;
          } else {
            return null; // Wrong delimiter.
          }
        }

        // Read a group, one to four hex digits.
        int value = 0;
        groupOffset = i;
        for (; i < limit; i++) {
          char c = input.charAt(i);
          int hexDigit = decodeHexDigit(c);
          if (hexDigit == -1) break;
          value = (value << 4) + hexDigit;
        }
        int groupLength = i - groupOffset;
        if (groupLength == 0 || groupLength > 4) return null; // Group is the wrong size.

        // We've successfully read a group. Assign its value to our byte array.
        address[b++] = (byte) ((value >>> 8) & 0xff);
        address[b++] = (byte) (value & 0xff);
      }

      // All done. If compression happened, we need to move bytes to the right place in the
      // address. Here's a sample:
      //
      //      input: "1111:2222:3333::7777:8888"
      //     before: { 11, 11, 22, 22, 33, 33, 00, 00, 77, 77, 88, 88, 00, 00, 00, 00  }
      //   compress: 6
      //          b: 10
      //      after: { 11, 11, 22, 22, 33, 33, 00, 00, 00, 00, 00, 00, 77, 77, 88, 88 }
      //
      if (b != address.length) {
        if (compress == -1) return null; // Address didn't have compression or enough groups.
        System.arraycopy(address, compress, address, address.length - (b - compress), b - compress);
        Arrays.fill(address, compress, compress + (address.length - b), (byte) 0);
      }

      try {
        return InetAddress.getByAddress(address);
      } catch (UnknownHostException e) {
        throw new AssertionError();
      }
    }

    /** Decodes an IPv4 address suffix of an IPv6 address, like 1111::5555:6666:192.168.0.1. */
    private static boolean decodeIpv4Suffix(
        String input, int pos, int limit, byte[] address, int addressOffset) {
      int b = addressOffset;

      for (int i = pos; i < limit; ) {
        if (b == address.length) return false; // Too many groups.

        // Read a delimiter.
        if (b != addressOffset) {
          if (input.charAt(i) != '.') return false; // Wrong delimiter.
          i++;
        }

        // Read 1 or more decimal digits for a value in 0..255.
        int value = 0;
        int groupOffset = i;
        for (; i < limit; i++) {
          char c = input.charAt(i);
          if (c < '0' || c > '9') break;
          if (value == 0 && groupOffset != i) return false; // Reject unnecessary leading '0's.
          value = (value * 10) + c - '0';
          if (value > 255) return false; // Value out of range.
        }
        int groupLength = i - groupOffset;
        if (groupLength == 0) return false; // No digits.

        // We've successfully read a byte.
        address[b++] = (byte) value;
      }

      if (b != addressOffset + 4) return false; // Too few groups. We wanted exactly four.
      return true; // Success.
    }

    private static String domainToAscii(String input) {
      try {
        String result = IDN.toASCII(input).toLowerCase(Locale.US);
        if (result.isEmpty()) return null;
        // TODO: implement all label limits.
        return result;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private int port(String input, int pos, int limit) {
      try {
        String portString = encode(input, pos, limit, ""); // To skip '\n' etc.
        int i = Integer.parseInt(portString);
        if (i > 0 && i <= 65535) return i;
        return -1;
      } catch (NumberFormatException e) {
        return -1; // Invalid port.
      }
    }

    private void pathSegment(String input, int pos, int limit) {
      encode(pathBuilder, input, pos, limit, " \"<>^`{}|");
    }

    private String query(String input, int pos, int limit) {
      return encode(input, pos, limit, " \"'<>");
    }

    private String fragment(String input, int pos, int limit) {
      return encode(input, pos, limit, ""); // To skip '\n' etc.
    }
  }

  private static String decode(String encoded, int pos, int limit) {
    for (int i = pos; i < limit; i++) {
      if (encoded.charAt(i) == '%') {
        // Slow path: the character at i requires decoding!
        Buffer out = new Buffer();
        out.writeUtf8(encoded, pos, i);
        return decode(out, encoded, i, limit);
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return encoded.substring(pos, limit);
  }

  private static String decode(Buffer out, String encoded, int pos, int limit) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = encoded.codePointAt(i);
      if (codePoint == '%' && i + 2 < limit) {
        int d1 = decodeHexDigit(encoded.charAt(i + 1));
        int d2 = decodeHexDigit(encoded.charAt(i + 2));
        if (d1 != -1 && d2 != -1) {
          out.writeByte((d1 << 4) + d2);
          i += 2;
          continue;
        }
      }
      out.writeUtf8CodePoint(codePoint);
    }
    return out.readUtf8();
  }

  private static int decodeHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  }

  /**
   * Returns a substring of {@code input} on the range {@code [pos..limit)} with the following
   * transformations:
   * <ul>
   *   <li>Tabs, newlines, form feeds and carriage returns are skipped.
   *   <li>Characters in {@code encodeSet} are percent-encoded.
   *   <li>Control characters and non-ASCII characters are percent-encoded.
   *   <li>All other characters are copied without transformation.
   * </ul>
   */
  static String encode(String input, int pos, int limit, String encodeSet) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1) {
        // Slow path: the character at i requires encoding!
        StringBuilder out = new StringBuilder();
        out.append(input, pos, i);
        encode(out, input, i, limit, encodeSet);
        return out.toString();
      }
    }

    // Fast path: no characters in [pos..limit) required encoding.
    return input.substring(pos, limit);
  }

  static void encode(StringBuilder out, String input, int pos, int limit, String encodeSet) {
    Buffer utf8Buffer = null; // Lazily allocated.
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (codePoint == '\t'
          || codePoint == '\n'
          || codePoint == '\f'
          || codePoint == '\r') {
        // Skip this character.
      } else if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1) {
        // Percent encode this character.
        if (utf8Buffer == null) {
          utf8Buffer = new Buffer();
        }
        utf8Buffer.writeUtf8CodePoint(codePoint);
        while (!utf8Buffer.exhausted()) {
          int b = utf8Buffer.readByte() & 0xff;
          out.append('%');
          out.append(HEX_DIGITS[(b >> 4) & 0xf]);
          out.append(HEX_DIGITS[b & 0xf]);
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        out.append((char) codePoint);
      }
    }
  }
}
