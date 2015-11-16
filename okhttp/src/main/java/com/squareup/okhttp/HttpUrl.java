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

import java.net.IDN;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okio.Buffer;

/**
 * A uniform resource locator (URL) with a scheme of either {@code http} or {@code https}. Use this
 * class to compose and decompose Internet addresses. For example, this code will compose and print
 * a URL for Google search: <pre>   {@code
 *
 *   HttpUrl url = new HttpUrl.Builder()
 *       .scheme("https")
 *       .host("www.google.com")
 *       .addPathSegment("search")
 *       .addQueryParameter("q", "polar bears")
 *       .build();
 *   System.out.println(url);
 * }</pre>
 *
 * which prints: <pre>   {@code
 *
 *     https://www.google.com/search?q=polar%20bears
 * }</pre>
 *
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 * <pre>   {@code
 *
 *   HttpUrl url = HttpUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 *   for (int i = 0, size = url.querySize(); i < size; i++) {
 *     System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 *   }
 * }</pre>
 *
 * which prints: <pre>   {@code
 *
 *   q: cute #puppies
 *   f: images
 * }</pre>
 *
 * In addition to composing URLs from their component parts and decomposing URLs into their
 * component parts, this class implements relative URL resolution: what address you'd reach by
 * clicking a relative link on a specified page. For example: <pre>   {@code
 *
 *   HttpUrl base = HttpUrl.parse("https://www.youtube.com/user/WatchTheDaily/videos");
 *   HttpUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 *   System.out.println(link);
 * }</pre>
 *
 * which prints: <pre>   {@code
 *
 *   https://www.youtube.com/watch?v=cbP2N1BQdYc
 * }</pre>
 *
 * <h3>What's in a URL?</h3>
 *
 * A URL has several components.
 *
 * <h4>Scheme</h4>
 * Sometimes referred to as <i>protocol</i>, A URL's scheme describes what mechanism should be used
 * to retrieve the resource. Although URLs have many schemes ({@code mailto}, {@code file}, {@code
 * ftp}), this class only supports {@code http} and {@code https}. Use {@link URI java.net.URI} for
 * URLs with arbitrary schemes.
 *
 * <h4>Username and Password</h4>
 * Username and password are either present, or the empty string {@code ""} if absent. This class
 * offers no mechanism to differentiate empty from absent. Neither of these components are popular
 * in practice. Typically HTTP applications use other mechanisms for user identification and
 * authentication.
 *
 * <h4>Host</h4>
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like
 * {@code square.com} or {@code localhost}, an IPv4 address like {@code 192.168.0.1}, or an IPv6
 * address like {@code ::1}.
 *
 * <p>Usually a webserver is reachable with multiple identifiers: its IP addresses, registered
 * domain names, and even {@code localhost} when connecting from the server itself. Each of a
 * webserver's names is a distinct URL and they are not interchangeable. For example, even if
 * {@code http://square.github.io/dagger} and {@code http://google.github.io/dagger} are served by
 * the same IP address, the two URLs identify different resources.
 *
 * <h4>Port</h4>
 * The port used to connect to the webserver. By default this is 80 for HTTP and 443 for HTTPS. This
 * class never returns -1 for the port: if no port is explicitly specified in the URL then the
 * scheme's default is used.
 *
 * <h4>Path</h4>
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "/square/okhttp/issues/1486". Each path segment is prefixed with "/". This class offers methods
 * to compose and decompose paths by segment. If a path's last segment is the empty string, then the
 * path ends with "/". This class always builds non-empty paths: if the path is omitted it defaults
 * to "/", which is a path whose only segment is the empty string.
 *
 * <h4>Query</h4>
 * The query is optional: it can be null, empty, or non-empty. For many HTTP URLs the query string
 * is subdivided into a collection of name-value parameters. This class offers methods to set the
 * query as the single string, or as individual name-value parameters. With name-value parameters
 * the values are optional and names may be repeated.
 *
 * <h4>Fragment</h4>
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and query
 * the fragment is not sent to the webserver: it's private to the client.
 *
 * <h3>Encoding</h3>
 * Each component must be encoded before it is embedded in the complete URL. As we saw above, the
 * string {@code cute #puppies} is encoded as {@code cute%20%23puppies} when used as a query
 * parameter value.
 *
 * <h4>Percent encoding</h4>
 * Percent encoding replaces a character (like {@code \ud83c\udf69}) with its UTF-8 hex bytes (like
 * {@code %F0%9F%8D%A9}). This approach works for whitespace characters, control characters,
 * non-ASCII characters, and characters that already have another meaning in a particular context.
 *
 * <p>Percent encoding is used in every URL component except for the hostname. But the set of
 * characters that need to be encoded is different for each component. For example, the path
 * component must escape all of its {@code ?} characters, otherwise it could be interpreted as the
 * start of the URL's query. But within the query and fragment components, the {@code ?} character
 * doesn't delimit anything and doesn't need to be escaped. <pre>   {@code
 *
 *   HttpUrl url = HttpUrl.parse("http://who-let-the-dogs.out").newBuilder()
 *       .addPathSegment("_Who?_")
 *       .query("_Who?_")
 *       .fragment("_Who?_")
 *       .build();
 *   System.out.println(url);
 * }</pre>
 *
 * This prints: <pre>   {@code
 *
 *   http://who-let-the-dogs.out/_Who%3F_?_Who?_#_Who?_
 * }</pre>
 *
 * When parsing URLs that lack percent encoding where it is required, this class will percent encode
 * the offending characters.
 *
 * <h4>IDNA Mapping and Punycode encoding</h4>
 * Hostnames have different requirements and use a different encoding scheme. It consists of IDNA
 * mapping and Punycode encoding.
 *
 * <p>In order to avoid confusion and discourage phishing attacks,
 * <a href="http://www.unicode.org/reports/tr46/#ToASCII">IDNA Mapping</a> transforms names to avoid
 * confusing characters. This includes basic case folding: transforming shouting {@code SQUARE.COM}
 * into cool and casual {@code square.com}. It also handles more exotic characters. For example, the
 * Unicode trademark sign (™) could be confused for the letters "TM" in {@code http://ho™mail.com}.
 * To mitigate this, the single character (™) maps to the string (tm). There is similar policy for
 * all of the 1.1 million Unicode code points. Note that some code points such as "\ud83c\udf69" are
 * not mapped and cannot be used in a hostname.
 *
 * <p><a href="http://ietf.org/rfc/rfc3492.txt">Punycode</a> converts a Unicode string to an ASCII
 * string to make international domain names work everywhere. For example, "σ" encodes as
 * "xn--4xa". The encoded string is not human readable, but can be used with classes like {@link
 * InetAddress} to establish connections.
 *
 * <h3>Why another URL model?</h3>
 * Java includes both {@link URL java.net.URL} and {@link URI java.net.URI}. We offer a new URL
 * model to address problems that the others don't.
 *
 * <h4>Different URLs should be different</h4>
 * Although they have different content, {@code java.net.URL} considers the following two URLs
 * equal, and the {@link Object#equals equals()} method between them returns true:
 * <ul>
 *   <li>http://square.github.io/
 *   <li>http://google.github.io/
 * </ul>
 * This is because those two hosts share the same IP address. This is an old, bad design decision
 * that makes {@code java.net.URL} unusable for many things. It shouldn't be used as a {@link
 * java.util.Map Map} key or in a {@link Set}. Doing so is both inefficient because equality may
 * require a DNS lookup, and incorrect because unequal URLs may be equal because of how they are
 * hosted.
 *
 * <h4>Equal URLs should be equal</h4>
 * These two URLs are semantically identical, but {@code java.net.URI} disagrees:
 * <ul>
 *   <li>http://host:80/
 *   <li>http://host
 * </ul>
 * Both the unnecessary port specification ({@code :80}) and the absent trailing slash ({@code /})
 * cause URI to bucket the two URLs separately. This harms URI's usefulness in collections. Any
 * application that stores information-per-URL will need to either canonicalize manually, or suffer
 * unnecessary redundancy for such URLs.
 *
 * <p>Because they don't attempt canonical form, these classes are surprisingly difficult to use
 * securely. Suppose you're building a webservice that checks that incoming paths are prefixed
 * "/static/images/" before serving the corresponding assets from the filesystem. <pre>   {@code
 *
 *   String attack = "http://example.com/static/images/../../../../../etc/passwd";
 *   System.out.println(new URL(attack).getPath());
 *   System.out.println(new URI(attack).getPath());
 *   System.out.println(HttpUrl.parse(attack).path());
 * }</pre>
 *
 * By canonicalizing the input paths, they are complicit in directory traversal attacks. Code that
 * checks only the path prefix may suffer!
 * <pre>   {@code
 *
 *    /static/images/../../../../../etc/passwd
 *    /static/images/../../../../../etc/passwd
 *    /etc/passwd
 * }</pre>
 *
 * <h4>If it works on the web, it should work in your application</h4>
 * The {@code java.net.URI} class is strict around what URLs it accepts. It rejects URLs like
 * "http://example.com/abc|def" because the '|' character is unsupported. This class is more
 * forgiving: it will automatically percent-encode the '|', yielding "http://example.com/abc%7Cdef".
 * This kind behavior is consistent with web browsers. {@code HttpUrl} prefers consistency with
 * major web browsers over consistency with obsolete specifications.
 *
 * <h4>Paths and Queries should decompose</h4>
 * Neither of the built-in URL models offer direct access to path segments or query parameters.
 * Manually using {@code StringBuilder} to assemble these components is cumbersome: do '+'
 * characters get silently replaced with spaces? If a query parameter contains a '&amp;', does that
 * get escaped? By offering methods to read and write individual query parameters directly,
 * application developers are saved from the hassles of encoding and decoding.
 *
 * <h4>Plus a modern API</h4>
 * The URL (JDK1.0) and URI (Java 1.4) classes predate builders and instead use telescoping
 * constructors. For example, there's no API to compose a URI with a custom port without also
 * providing a query and fragment.
 *
 * <p>Instances of {@link HttpUrl} are well-formed and always have a scheme, host, and path. With
 * {@code java.net.URL} it's possible to create an awkward URL like {@code http:/} with scheme and
 * path but no hostname. Building APIs that consume such malformed values is difficult!
 *
 * <p>This class has a modern API. It avoids punitive checked exceptions: {@link #parse parse()}
 * returns null if the input is an invalid URL. You can even be explicit about whether each
 * component has been encoded already.
 */
public final class HttpUrl {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
  static final String USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#";
  static final String PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#";
  static final String PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#";
  static final String QUERY_ENCODE_SET = " \"'<>#";
  static final String QUERY_COMPONENT_ENCODE_SET = " \"'<>#&=";
  static final String CONVERT_TO_URI_ENCODE_SET = "^`{}|\\";
  static final String FORM_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#&!$(),~";
  static final String FRAGMENT_ENCODE_SET = "";

  /** Either "http" or "https". */
  private final String scheme;

  /** Decoded username. */
  private final String username;

  /** Decoded password. */
  private final String password;

  /** Canonical hostname. */
  private final String host;

  /** Either 80, 443 or a user-specified port. In range [1..65535]. */
  private final int port;

  /**
   * A list of canonical path segments. This list always contains at least one element, which may
   * be the empty string. Each segment is formatted with a leading '/', so if path segments were
   * ["a", "b", ""], then the encoded path would be "/a/b/".
   */
  private final List<String> pathSegments;

  /**
   * Alternating, decoded query names and values, or null for no query. Names may be empty or
   * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
   * empty, or non-empty.
   */
  private final List<String> queryNamesAndValues;

  /** Decoded fragment. */
  private final String fragment;

  /** Canonical URL. */
  private final String url;

  private HttpUrl(Builder builder) {
    this.scheme = builder.scheme;
    this.username = percentDecode(builder.encodedUsername);
    this.password = percentDecode(builder.encodedPassword);
    this.host = builder.host;
    this.port = builder.effectivePort();
    this.pathSegments = percentDecode(builder.encodedPathSegments);
    this.queryNamesAndValues = builder.encodedQueryNamesAndValues != null
        ? percentDecode(builder.encodedQueryNamesAndValues)
        : null;
    this.fragment = builder.encodedFragment != null
        ? percentDecode(builder.encodedFragment)
        : null;
    this.url = builder.toString();
  }

  /** Returns this URL as a {@link URL java.net.URL}. */
  public URL url() {
    try {
      return new URL(url);
    } catch (MalformedURLException e) {
      throw new RuntimeException(e); // Unexpected!
    }
  }

  /**
   * Attempt to convert this URL to a {@link URI java.net.URI}. This method throws an unchecked
   * {@link IllegalStateException} if the URL it holds isn't valid by URI's overly-stringent
   * standard. For example, URI rejects paths containing the '[' character. Consult that class for
   * the exact rules of what URLs are permitted.
   */
  public URI uri() {
    try {
      String uriSafeUrl = canonicalize(url, CONVERT_TO_URI_ENCODE_SET, true, false);
      return new URI(uriSafeUrl);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("not valid as a java.net.URI: " + url);
    }
  }

  /** Returns either "http" or "https". */
  public String scheme() {
    return scheme;
  }

  public boolean isHttps() {
    return "https".equals(scheme);
  }

  /** Returns the username, or an empty string if none is set. */
  public String encodedUsername() {
    if (username.isEmpty()) return "";
    int usernameStart = scheme.length() + 3; // "://".length() == 3.
    int usernameEnd = delimiterOffset(url, usernameStart, url.length(), ":@");
    return url.substring(usernameStart, usernameEnd);
  }

  public String username() {
    return username;
  }

  /** Returns the password, or an empty string if none is set. */
  public String encodedPassword() {
    if (password.isEmpty()) return "";
    int passwordStart = url.indexOf(':', scheme.length() + 3) + 1;
    int passwordEnd = url.indexOf('@');
    return url.substring(passwordStart, passwordEnd);
  }

  /** Returns the decoded password, or an empty string if none is present. */
  public String password() {
    return password;
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
    if ("http".equals(scheme)) {
      return 80;
    } else if ("https".equals(scheme)) {
      return 443;
    } else {
      return -1;
    }
  }

  public int pathSize() {
    return pathSegments.size();
  }

  /**
   * Returns the entire path of this URL, encoded for use in HTTP resource resolution. The
   * returned path is always nonempty and is prefixed with {@code /}.
   */
  public String encodedPath() {
    int pathStart = url.indexOf('/', scheme.length() + 3); // "://".length() == 3.
    int pathEnd = delimiterOffset(url, pathStart, url.length(), "?#");
    return url.substring(pathStart, pathEnd);
  }

  static void pathSegmentsToString(StringBuilder out, List<String> pathSegments) {
    for (int i = 0, size = pathSegments.size(); i < size; i++) {
      out.append('/');
      out.append(pathSegments.get(i));
    }
  }

  public List<String> encodedPathSegments() {
    int pathStart = url.indexOf('/', scheme.length() + 3);
    int pathEnd = delimiterOffset(url, pathStart, url.length(), "?#");
    List<String> result = new ArrayList<>();
    for (int i = pathStart; i < pathEnd; ) {
      i++; // Skip the '/'.
      int segmentEnd = delimiterOffset(url, i, pathEnd, "/");
      result.add(url.substring(i, segmentEnd));
      i = segmentEnd;
    }
    return result;
  }

  public List<String> pathSegments() {
    return pathSegments;
  }

  /**
   * Returns the query of this URL, encoded for use in HTTP resource resolution. The returned string
   * may be null (for URLs with no query), empty (for URLs with an empty query) or non-empty (all
   * other URLs).
   */
  public String encodedQuery() {
    if (queryNamesAndValues == null) return null; // No query.
    int queryStart = url.indexOf('?') + 1;
    int queryEnd = delimiterOffset(url, queryStart + 1, url.length(), "#");
    return url.substring(queryStart, queryEnd);
  }

  static void namesAndValuesToQueryString(StringBuilder out, List<String> namesAndValues) {
    for (int i = 0, size = namesAndValues.size(); i < size; i += 2) {
      String name = namesAndValues.get(i);
      String value = namesAndValues.get(i + 1);
      if (i > 0) out.append('&');
      out.append(name);
      if (value != null) {
        out.append('=');
        out.append(value);
      }
    }
  }

  /**
   * Cuts {@code encodedQuery} up into alternating parameter names and values. This divides a
   * query string like {@code subject=math&easy&problem=5-2=3} into the list {@code ["subject",
   * "math", "easy", null, "problem", "5-2=3"]}. Note that values may be null and may contain
   * '=' characters.
   */
  static List<String> queryStringToNamesAndValues(String encodedQuery) {
    List<String> result = new ArrayList<>();
    for (int pos = 0; pos <= encodedQuery.length(); ) {
      int ampersandOffset = encodedQuery.indexOf('&', pos);
      if (ampersandOffset == -1) ampersandOffset = encodedQuery.length();

      int equalsOffset = encodedQuery.indexOf('=', pos);
      if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
        result.add(encodedQuery.substring(pos, ampersandOffset));
        result.add(null); // No value for this name.
      } else {
        result.add(encodedQuery.substring(pos, equalsOffset));
        result.add(encodedQuery.substring(equalsOffset + 1, ampersandOffset));
      }
      pos = ampersandOffset + 1;
    }
    return result;
  }

  public String query() {
    if (queryNamesAndValues == null) return null; // No query.
    StringBuilder result = new StringBuilder();
    namesAndValuesToQueryString(result, queryNamesAndValues);
    return result.toString();
  }

  public int querySize() {
    return queryNamesAndValues != null ? queryNamesAndValues.size() / 2 : 0;
  }

  /**
   * Returns the first query parameter named {@code name} decoded using UTF-8, or null if there is
   * no such query parameter.
   */
  public String queryParameter(String name) {
    if (queryNamesAndValues == null) return null;
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      if (name.equals(queryNamesAndValues.get(i))) {
        return queryNamesAndValues.get(i + 1);
      }
    }
    return null;
  }

  public Set<String> queryParameterNames() {
    if (queryNamesAndValues == null) return Collections.emptySet();
    Set<String> result = new LinkedHashSet<>();
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      result.add(queryNamesAndValues.get(i));
    }
    return Collections.unmodifiableSet(result);
  }

  public List<String> queryParameterValues(String name) {
    if (queryNamesAndValues == null) return Collections.emptyList();
    List<String> result = new ArrayList<>();
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      if (name.equals(queryNamesAndValues.get(i))) {
        result.add(queryNamesAndValues.get(i + 1));
      }
    }
    return Collections.unmodifiableList(result);
  }

  public String queryParameterName(int index) {
    return queryNamesAndValues.get(index * 2);
  }

  public String queryParameterValue(int index) {
    return queryNamesAndValues.get(index * 2 + 1);
  }

  public String encodedFragment() {
    if (fragment == null) return null;
    int fragmentStart = url.indexOf('#') + 1;
    return url.substring(fragmentStart);
  }

  public String fragment() {
    return fragment;
  }

  /** Returns the URL that would be retrieved by following {@code link} from this URL. */
  public HttpUrl resolve(String link) {
    Builder builder = new Builder();
    Builder.ParseResult result = builder.parse(this, link);
    return result == Builder.ParseResult.SUCCESS ? builder.build() : null;
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    result.scheme = scheme;
    result.encodedUsername = encodedUsername();
    result.encodedPassword = encodedPassword();
    result.host = host;
    // If we're set to a default port, unset it, in case of a scheme change.
    if (port == defaultPort(scheme)) {
      result.port = -1;
    } else {
      result.port = port;
    }
    result.encodedPathSegments.clear();
    result.encodedPathSegments.addAll(encodedPathSegments());
    result.encodedQuery(encodedQuery());
    result.encodedFragment = encodedFragment();
    return result;
  }

  /**
   * Returns a new {@code HttpUrl} representing {@code url} if it is a well-formed HTTP or HTTPS
   * URL, or null if it isn't.
   */
  public static HttpUrl parse(String url) {
    Builder builder = new Builder();
    Builder.ParseResult result = builder.parse(null, url);
    return result == Builder.ParseResult.SUCCESS ? builder.build() : null;
  }

  /**
   * Returns an {@link HttpUrl} for {@code url} if its protocol is {@code http} or {@code https}, or
   * null if it has any other protocol.
   */
  public static HttpUrl get(URL url) {
    return parse(url.toString());
  }

  /**
   * Returns a new {@code HttpUrl} representing {@code url} if it is a well-formed HTTP or HTTPS
   * URL, or throws an exception if it isn't.
   *
   * @throws MalformedURLException if there was a non-host related URL issue
   * @throws UnknownHostException if the host was invalid
   */
  static HttpUrl getChecked(String url) throws MalformedURLException, UnknownHostException {
    Builder builder = new Builder();
    Builder.ParseResult result = builder.parse(null, url);
    switch (result) {
      case SUCCESS:
        return builder.build();
      case INVALID_HOST:
        throw new UnknownHostException("Invalid host: " + url);
      case UNSUPPORTED_SCHEME:
      case MISSING_SCHEME:
      case INVALID_PORT:
      default:
        throw new MalformedURLException("Invalid URL: " + result + " for " + url);
    }
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
    String encodedUsername = "";
    String encodedPassword = "";
    String host;
    int port = -1;
    final List<String> encodedPathSegments = new ArrayList<>();
    List<String> encodedQueryNamesAndValues;
    String encodedFragment;

    public Builder() {
      encodedPathSegments.add(""); // The default path is '/' which needs a trailing space.
    }

    public Builder scheme(String scheme) {
      if (scheme == null) {
        throw new IllegalArgumentException("scheme == null");
      } else if ("http".equalsIgnoreCase(scheme)) {
        this.scheme = "http";
      } else if ("https".equalsIgnoreCase(scheme)) {
        this.scheme = "https";
      } else {
        throw new IllegalArgumentException("unexpected scheme: " + scheme);
      }
      return this;
    }

    public Builder username(String username) {
      if (username == null) throw new IllegalArgumentException("username == null");
      this.encodedUsername = canonicalize(username, USERNAME_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedUsername(String encodedUsername) {
      if (encodedUsername == null) throw new IllegalArgumentException("encodedUsername == null");
      this.encodedUsername = canonicalize(encodedUsername, USERNAME_ENCODE_SET, true, false);
      return this;
    }

    public Builder password(String password) {
      if (password == null) throw new IllegalArgumentException("password == null");
      this.encodedPassword = canonicalize(password, PASSWORD_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedPassword(String encodedPassword) {
      if (encodedPassword == null) throw new IllegalArgumentException("encodedPassword == null");
      this.encodedPassword = canonicalize(encodedPassword, PASSWORD_ENCODE_SET, true, false);
      return this;
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     *     address.
     */
    public Builder host(String host) {
      if (host == null) throw new IllegalArgumentException("host == null");
      String encoded = canonicalizeHost(host, 0, host.length());
      if (encoded == null) throw new IllegalArgumentException("unexpected host: " + host);
      this.host = encoded;
      return this;
    }

    public Builder port(int port) {
      if (port <= 0 || port > 65535) throw new IllegalArgumentException("unexpected port: " + port);
      this.port = port;
      return this;
    }

    int effectivePort() {
      return port != -1 ? port : defaultPort(scheme);
    }

    public Builder addPathSegment(String pathSegment) {
      if (pathSegment == null) throw new IllegalArgumentException("pathSegment == null");
      push(pathSegment, 0, pathSegment.length(), false, false);
      return this;
    }

    public Builder addEncodedPathSegment(String encodedPathSegment) {
      if (encodedPathSegment == null) {
        throw new IllegalArgumentException("encodedPathSegment == null");
      }
      push(encodedPathSegment, 0, encodedPathSegment.length(), false, true);
      return this;
    }

    public Builder setPathSegment(int index, String pathSegment) {
      if (pathSegment == null) throw new IllegalArgumentException("pathSegment == null");
      String canonicalPathSegment = canonicalize(
          pathSegment, 0, pathSegment.length(), PATH_SEGMENT_ENCODE_SET, false, false);
      if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
        throw new IllegalArgumentException("unexpected path segment: " + pathSegment);
      }
      encodedPathSegments.set(index, canonicalPathSegment);
      return this;
    }

    public Builder setEncodedPathSegment(int index, String encodedPathSegment) {
      if (encodedPathSegment == null) {
        throw new IllegalArgumentException("encodedPathSegment == null");
      }
      String canonicalPathSegment = canonicalize(encodedPathSegment,
          0, encodedPathSegment.length(), PATH_SEGMENT_ENCODE_SET, true, false);
      encodedPathSegments.set(index, canonicalPathSegment);
      if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
        throw new IllegalArgumentException("unexpected path segment: " + encodedPathSegment);
      }
      return this;
    }

    public Builder removePathSegment(int index) {
      encodedPathSegments.remove(index);
      if (encodedPathSegments.isEmpty()) {
        encodedPathSegments.add(""); // Always leave at least one '/'.
      }
      return this;
    }

    public Builder encodedPath(String encodedPath) {
      if (encodedPath == null) throw new IllegalArgumentException("encodedPath == null");
      if (!encodedPath.startsWith("/")) {
        throw new IllegalArgumentException("unexpected encodedPath: " + encodedPath);
      }
      resolvePath(encodedPath, 0, encodedPath.length());
      return this;
    }

    public Builder query(String query) {
      this.encodedQueryNamesAndValues = query != null
          ? queryStringToNamesAndValues(canonicalize(query, QUERY_ENCODE_SET, false, true))
          : null;
      return this;
    }

    public Builder encodedQuery(String encodedQuery) {
      this.encodedQueryNamesAndValues = encodedQuery != null
          ? queryStringToNamesAndValues(canonicalize(encodedQuery, QUERY_ENCODE_SET, true, true))
          : null;
      return this;
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    public Builder addQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = new ArrayList<>();
      encodedQueryNamesAndValues.add(canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true));
      encodedQueryNamesAndValues.add(value != null
          ? canonicalize(value, QUERY_COMPONENT_ENCODE_SET, false, true)
          : null);
      return this;
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    public Builder addEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = new ArrayList<>();
      encodedQueryNamesAndValues.add(
          canonicalize(encodedName, QUERY_COMPONENT_ENCODE_SET, true, true));
      encodedQueryNamesAndValues.add(encodedValue != null
          ? canonicalize(encodedValue, QUERY_COMPONENT_ENCODE_SET, true, true)
          : null);
      return this;
    }

    public Builder setQueryParameter(String name, String value) {
      removeAllQueryParameters(name);
      addQueryParameter(name, value);
      return this;
    }

    public Builder setEncodedQueryParameter(String encodedName, String encodedValue) {
      removeAllEncodedQueryParameters(encodedName);
      addEncodedQueryParameter(encodedName, encodedValue);
      return this;
    }

    public Builder removeAllQueryParameters(String name) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (encodedQueryNamesAndValues == null) return this;
      String nameToRemove = canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true);
      removeAllCanonicalQueryParameters(nameToRemove);
      return this;
    }

    public Builder removeAllEncodedQueryParameters(String encodedName) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedQueryNamesAndValues == null) return this;
      removeAllCanonicalQueryParameters(
          canonicalize(encodedName, QUERY_COMPONENT_ENCODE_SET, true, true));
      return this;
    }

    private void removeAllCanonicalQueryParameters(String canonicalName) {
      for (int i = encodedQueryNamesAndValues.size() - 2; i >= 0; i -= 2) {
        if (canonicalName.equals(encodedQueryNamesAndValues.get(i))) {
          encodedQueryNamesAndValues.remove(i + 1);
          encodedQueryNamesAndValues.remove(i);
          if (encodedQueryNamesAndValues.isEmpty()) {
            encodedQueryNamesAndValues = null;
            return;
          }
        }
      }
    }

    public Builder fragment(String fragment) {
      if (fragment == null) throw new IllegalArgumentException("fragment == null");
      this.encodedFragment = canonicalize(fragment, FRAGMENT_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedFragment(String encodedFragment) {
      if (encodedFragment == null) throw new IllegalArgumentException("encodedFragment == null");
      this.encodedFragment = canonicalize(encodedFragment, FRAGMENT_ENCODE_SET, true, false);
      return this;
    }

    public HttpUrl build() {
      if (scheme == null) throw new IllegalStateException("scheme == null");
      if (host == null) throw new IllegalStateException("host == null");
      return new HttpUrl(this);
    }

    @Override public String toString() {
      StringBuilder result = new StringBuilder();
      result.append(scheme);
      result.append("://");

      if (!encodedUsername.isEmpty() || !encodedPassword.isEmpty()) {
        result.append(encodedUsername);
        if (!encodedPassword.isEmpty()) {
          result.append(':');
          result.append(encodedPassword);
        }
        result.append('@');
      }

      if (host.indexOf(':') != -1) {
        // Host is an IPv6 address.
        result.append('[');
        result.append(host);
        result.append(']');
      } else {
        result.append(host);
      }

      int effectivePort = effectivePort();
      if (effectivePort != defaultPort(scheme)) {
        result.append(':');
        result.append(effectivePort);
      }

      pathSegmentsToString(result, encodedPathSegments);

      if (encodedQueryNamesAndValues != null) {
        result.append('?');
        namesAndValuesToQueryString(result, encodedQueryNamesAndValues);
      }

      if (encodedFragment != null) {
        result.append('#');
        result.append(encodedFragment);
      }

      return result.toString();
    }

    enum ParseResult {
      SUCCESS,
      MISSING_SCHEME,
      UNSUPPORTED_SCHEME,
      INVALID_PORT,
      INVALID_HOST,
    }

    ParseResult parse(HttpUrl base, String input) {
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
          return ParseResult.UNSUPPORTED_SCHEME; // Not an HTTP scheme.
        }
      } else if (base != null) {
        this.scheme = base.scheme;
      } else {
        return ParseResult.MISSING_SCHEME; // No scheme.
      }

      // Authority.
      boolean hasUsername = false;
      boolean hasPassword = false;
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
              if (!hasPassword) {
                int passwordColonOffset = delimiterOffset(
                    input, pos, componentDelimiterOffset, ":");
                String canonicalUsername = canonicalize(
                    input, pos, passwordColonOffset, USERNAME_ENCODE_SET, true, false);
                this.encodedUsername = hasUsername
                    ? this.encodedUsername + "%40" + canonicalUsername
                    : canonicalUsername;
                if (passwordColonOffset != componentDelimiterOffset) {
                  hasPassword = true;
                  this.encodedPassword = canonicalize(input, passwordColonOffset + 1,
                      componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false);
                }
                hasUsername = true;
              } else {
                this.encodedPassword = this.encodedPassword + "%40" + canonicalize(
                    input, pos, componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false);
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
                this.host = canonicalizeHost(input, pos, portColonOffset);
                this.port = parsePort(input, portColonOffset + 1, componentDelimiterOffset);
                if (this.port == -1) return ParseResult.INVALID_PORT; // Invalid port.
              } else {
                this.host = canonicalizeHost(input, pos, portColonOffset);
                this.port = defaultPort(this.scheme);
              }
              if (this.host == null) return ParseResult.INVALID_HOST; // Invalid host.
              pos = componentDelimiterOffset;
              break authority;
          }
        }
      } else {
        // This is a relative link. Copy over all authority components. Also maybe the path & query.
        this.encodedUsername = base.encodedUsername();
        this.encodedPassword = base.encodedPassword();
        this.host = base.host;
        this.port = base.port;
        this.encodedPathSegments.clear();
        this.encodedPathSegments.addAll(base.encodedPathSegments());
        if (pos == limit || input.charAt(pos) == '#') {
          encodedQuery(base.encodedQuery());
        }
      }

      // Resolve the relative path.
      int pathDelimiterOffset = delimiterOffset(input, pos, limit, "?#");
      resolvePath(input, pos, pathDelimiterOffset);
      pos = pathDelimiterOffset;

      // Query.
      if (pos < limit && input.charAt(pos) == '?') {
        int queryDelimiterOffset = delimiterOffset(input, pos, limit, "#");
        this.encodedQueryNamesAndValues = queryStringToNamesAndValues(canonicalize(
            input, pos + 1, queryDelimiterOffset, QUERY_ENCODE_SET, true, true));
        pos = queryDelimiterOffset;
      }

      // Fragment.
      if (pos < limit && input.charAt(pos) == '#') {
        this.encodedFragment = canonicalize(
            input, pos + 1, limit, FRAGMENT_ENCODE_SET, true, false);
      }

      return ParseResult.SUCCESS;
    }

    private void resolvePath(String input, int pos, int limit) {
      // Read a delimiter.
      if (pos == limit) {
        // Empty path: keep the base path as-is.
        return;
      }
      char c = input.charAt(pos);
      if (c == '/' || c == '\\') {
        // Absolute path: reset to the default "/".
        encodedPathSegments.clear();
        encodedPathSegments.add("");
        pos++;
      } else {
        // Relative path: clear everything after the last '/'.
        encodedPathSegments.set(encodedPathSegments.size() - 1, "");
      }

      // Read path segments.
      for (int i = pos; i < limit; ) {
        int pathSegmentDelimiterOffset = delimiterOffset(input, i, limit, "/\\");
        boolean segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit;
        push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true);
        i = pathSegmentDelimiterOffset;
        if (segmentHasTrailingSlash) i++;
      }
    }

    /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
    private void push(String input, int pos, int limit, boolean addTrailingSlash,
        boolean alreadyEncoded) {
      String segment = canonicalize(
          input, pos, limit, PATH_SEGMENT_ENCODE_SET, alreadyEncoded, false);
      if (isDot(segment)) {
        return; // Skip '.' path segments.
      }
      if (isDotDot(segment)) {
        pop();
        return;
      }
      if (encodedPathSegments.get(encodedPathSegments.size() - 1).isEmpty()) {
        encodedPathSegments.set(encodedPathSegments.size() - 1, segment);
      } else {
        encodedPathSegments.add(segment);
      }
      if (addTrailingSlash) {
        encodedPathSegments.add("");
      }
    }

    private boolean isDot(String input) {
      return ".".equals(input) || "%2e".equalsIgnoreCase(input);
    }

    private boolean isDotDot(String input) {
      return "..".equals(input)
          || "%2e.".equalsIgnoreCase(input)
          || ".%2e".equalsIgnoreCase(input)
          || "%2e%2e".equalsIgnoreCase(input);
    }

    /**
     * Removes a path segment. When this method returns the last segment is always "", which means
     * the encoded path will have a trailing '/'.
     *
     * <p>Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from
     * ["a", "b", "c", ""] to ["a", "b", ""].
     *
     * <p>Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
     * to ["a", "b", ""].
     */
    private void pop() {
      String removed = encodedPathSegments.remove(encodedPathSegments.size() - 1);

      // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
      if (removed.isEmpty() && !encodedPathSegments.isEmpty()) {
        encodedPathSegments.set(encodedPathSegments.size() - 1, "");
      } else {
        encodedPathSegments.add("");
      }
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
            || (c >= '0' && c <= '9')
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

    private static String canonicalizeHost(String input, int pos, int limit) {
      // Start by percent decoding the host. The WHATWG spec suggests doing this only after we've
      // checked for IPv6 square braces. But Chrome does it first, and that's more lenient.
      String percentDecoded = percentDecode(input, pos, limit);

      // If the input is encased in square braces "[...]", drop 'em. We have an IPv6 address.
      if (percentDecoded.startsWith("[") && percentDecoded.endsWith("]")) {
        InetAddress inetAddress = decodeIpv6(percentDecoded, 1, percentDecoded.length() - 1);
        if (inetAddress == null) return null;
        byte[] address = inetAddress.getAddress();
        if (address.length == 16) return inet6AddressToAscii(address);
        throw new AssertionError();
      }

      return domainToAscii(percentDecoded);
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
        throw new AssertionError(e);
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

    /**
     * Performs IDN ToASCII encoding and canonicalize the result to lowercase. e.g. This converts
     * {@code ☃.net} to {@code xn--n3h.net}, and {@code WwW.GoOgLe.cOm} to {@code www.google.com}.
     * {@code null} will be returned if the input cannot be ToASCII encoded or if the result
     * contains unsupported ASCII characters.
     */
    private static String domainToAscii(String input) {
      try {
        String result = IDN.toASCII(input).toLowerCase(Locale.US);
        if (result.isEmpty()) return null;

        if (result == null) return null;

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

    private static String inet6AddressToAscii(byte[] address) {
      // Go through the address looking for the longest run of 0s. Each group is 2-bytes.
      int longestRunOffset = -1;
      int longestRunLength = 0;
      for (int i = 0; i < address.length; i += 2) {
        int currentRunOffset = i;
        while (i < 16 && address[i] == 0 && address[i + 1] == 0) {
          i += 2;
        }
        int currentRunLength = i - currentRunOffset;
        if (currentRunLength > longestRunLength) {
          longestRunOffset = currentRunOffset;
          longestRunLength = currentRunLength;
        }
      }

      // Emit each 2-byte group in hex, separated by ':'. The longest run of zeroes is "::".
      Buffer result = new Buffer();
      for (int i = 0; i < address.length; ) {
        if (i == longestRunOffset) {
          result.writeByte(':');
          i += longestRunLength;
          if (i == 16) result.writeByte(':');
        } else {
          if (i > 0) result.writeByte(':');
          int group = (address[i] & 0xff) << 8 | address[i + 1] & 0xff;
          result.writeHexadecimalUnsignedLong(group);
          i += 2;
        }
      }
      return result.readUtf8();
    }

    private static int parsePort(String input, int pos, int limit) {
      try {
        // Canonicalize the port string to skip '\n' etc.
        String portString = canonicalize(input, pos, limit, "", false, false);
        int i = Integer.parseInt(portString);
        if (i > 0 && i <= 65535) return i;
        return -1;
      } catch (NumberFormatException e) {
        return -1; // Invalid port.
      }
    }
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

  static String percentDecode(String encoded) {
    return percentDecode(encoded, 0, encoded.length());
  }

  private List<String> percentDecode(List<String> list) {
    List<String> result = new ArrayList<>(list.size());
    for (String s : list) {
      result.add(s != null ? percentDecode(s) : null);
    }
    return Collections.unmodifiableList(result);
  }

  static String percentDecode(String encoded, int pos, int limit) {
    for (int i = pos; i < limit; i++) {
      char c = encoded.charAt(i);
      if (c == '%') {
        // Slow path: the character at i requires decoding!
        Buffer out = new Buffer();
        out.writeUtf8(encoded, pos, i);
        percentDecode(out, encoded, i, limit);
        return out.readUtf8();
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return encoded.substring(pos, limit);
  }

  static void percentDecode(Buffer out, String encoded, int pos, int limit) {
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
  }

  static int decodeHexDigit(char c) {
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
   *   <li>In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
   *   <li>Characters in {@code encodeSet} are percent-encoded.
   *   <li>Control characters and non-ASCII characters are percent-encoded.
   *   <li>All other characters are copied without transformation.
   * </ul>
   *
   * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
   * @param query true if to encode ' ' as '+', and '+' as "%2B".
   */
  static String canonicalize(String input, int pos, int limit, String encodeSet,
      boolean alreadyEncoded, boolean query) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1
          || (codePoint == '%' && !alreadyEncoded)
          || (query && codePoint == '+')) {
        // Slow path: the character at i requires encoding!
        Buffer out = new Buffer();
        out.writeUtf8(input, pos, i);
        canonicalize(out, input, i, limit, encodeSet, alreadyEncoded, query);
        return out.readUtf8();
      }
    }

    // Fast path: no characters in [pos..limit) required encoding.
    return input.substring(pos, limit);
  }

  static void canonicalize(Buffer out, String input, int pos, int limit,
      String encodeSet, boolean alreadyEncoded, boolean query) {
    Buffer utf8Buffer = null; // Lazily allocated.
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (alreadyEncoded
          && (codePoint == '\t' || codePoint == '\n' || codePoint == '\f' || codePoint == '\r')) {
        // Skip this character.
      } else if (query && codePoint == '+') {
        // HTML permits space to be encoded as '+'. We use '%20' to avoid special cases.
        out.writeUtf8(alreadyEncoded ? "%20" : "%2B");
      } else if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1
          || (codePoint == '%' && !alreadyEncoded)) {
        // Percent encode this character.
        if (utf8Buffer == null) {
          utf8Buffer = new Buffer();
        }
        utf8Buffer.writeUtf8CodePoint(codePoint);
        while (!utf8Buffer.exhausted()) {
          int b = utf8Buffer.readByte() & 0xff;
          out.writeByte('%');
          out.writeByte(HEX_DIGITS[(b >> 4) & 0xf]);
          out.writeByte(HEX_DIGITS[b & 0xf]);
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        out.writeUtf8CodePoint(codePoint);
      }
    }
  }

  static String canonicalize(
      String input, String encodeSet, boolean alreadyEncoded, boolean query) {
    return canonicalize(input, 0, input.length(), encodeSet, alreadyEncoded, query);
  }
}
