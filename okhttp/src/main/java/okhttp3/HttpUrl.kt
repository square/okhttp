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
package okhttp3

import okhttp3.HttpUrl.Companion.get
import okhttp3.HttpUrl.Companion.parse
import okhttp3.internal.Util
import okhttp3.internal.Util.decodeHexDigit
import okhttp3.internal.Util.delimiterOffset
import okhttp3.internal.Util.skipLeadingAsciiWhitespace
import okhttp3.internal.Util.skipTrailingAsciiWhitespace
import okhttp3.internal.Util.verifyAsIpAddress
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okio.Buffer
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/**
 * A uniform resource locator (URL) with a scheme of either `http` or `https`. Use this class to
 * compose and decompose Internet addresses. For example, this code will compose and print a URL for
 * Google search:
 *
 * ```
 * HttpUrl url = new HttpUrl.Builder()
 *     .scheme("https")
 *     .host("www.google.com")
 *     .addPathSegment("search")
 *     .addQueryParameter("q", "polar bears")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.google.com/search?q=polar%20bears
 * ```
 *
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 *
 * ```
 * HttpUrl url = HttpUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 * for (int i = 0, size = url.querySize(); i < size; i++) {
 *   System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 * }
 * ```
 *
 * which prints:
 *
 * ```
 * q: cute #puppies
 * f: images
 * ```
 *
 * In addition to composing URLs from their component parts and decomposing URLs into their
 * component parts, this class implements relative URL resolution: what address you'd reach by
 * clicking a relative link on a specified page. For example:
 *
 * ```
 * HttpUrl base = HttpUrl.parse("https://www.youtube.com/user/WatchTheDaily/videos");
 * HttpUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 * System.out.println(link);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.youtube.com/watch?v=cbP2N1BQdYc
 * ```
 *
 * ## What's in a URL?
 *
 * A URL has several components.
 *
 * ### Scheme
 *
 * Sometimes referred to as *protocol*, A URL's scheme describes what mechanism should be used to
 * retrieve the resource. Although URLs have many schemes (`mailto`, `file`, `ftp`), this class only
 * supports `http` and `https`. Use [java.net.URI][URI] for URLs with arbitrary schemes.
 *
 * ### Username and Password
 *
 * Username and password are either present, or the empty string `""` if absent. This class offers
 * no mechanism to differentiate empty from absent. Neither of these components are popular in
 * practice. Typically HTTP applications use other mechanisms for user identification and
 * authentication.
 *
 * ### Host
 *
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like
 * `square.com` or `localhost`, an IPv4 address like `192.168.0.1`, or an IPv6 address like `::1`.
 *
 * Usually a webserver is reachable with multiple identifiers: its IP addresses, registered
 * domain names, and even `localhost` when connecting from the server itself. Each of a web server's
 * names is a distinct URL and they are not interchangeable. For example, even if
 * `http://square.github.io/dagger` and `http://google.github.io/dagger` are served by the same IP
 * address, the two URLs identify different resources.
 *
 * ### Port
 *
 * The port used to connect to the web server. By default this is 80 for HTTP and 443 for HTTPS.
 * This class never returns -1 for the port: if no port is explicitly specified in the URL then the
 * scheme's default is used.
 *
 * ### Path
 *
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "/square/okhttp/issues/1486" and decompose into a list of segments like `["square", "okhttp",
 * "issues", "1486"]`.
 *
 * This class offers methods to compose and decompose paths by segment. It composes each path
 * from a list of segments by alternating between "/" and the encoded segment. For example the
 * segments `["a", "b"]` build "/a/b" and the segments `["a", "b", ""]` build "/a/b/".
 *
 * If a path's last segment is the empty string then the path ends with "/". This class always
 * builds non-empty paths: if the path is omitted it defaults to "/". The default path's segment
 * list is a single empty string: `[""]`.
 *
 * ### Query
 *
 * The query is optional: it can be null, empty, or non-empty. For many HTTP URLs the query string
 * is subdivided into a collection of name-value parameters. This class offers methods to set the
 * query as the single string, or as individual name-value parameters. With name-value parameters
 * the values are optional and names may be repeated.
 *
 * ### Fragment
 *
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and
 * query the fragment is not sent to the webserver: it's private to the client.
 *
 * ## Encoding
 *
 * Each component must be encoded before it is embedded in the complete URL. As we saw above, the
 * string `cute #puppies` is encoded as `cute%20%23puppies` when used as a query parameter value.
 *
 * ### Percent encoding
 *
 * Percent encoding replaces a character (like `\ud83c\udf69`) with its UTF-8 hex bytes (like
 * `%F0%9F%8D%A9`). This approach works for whitespace characters, control characters, non-ASCII
 * characters, and characters that already have another meaning in a particular context.
 *
 * Percent encoding is used in every URL component except for the hostname. But the set of
 * characters that need to be encoded is different for each component. For example, the path
 * component must escape all of its `?` characters, otherwise it could be interpreted as the
 * start of the URL's query. But within the query and fragment components, the `?` character
 * doesn't delimit anything and doesn't need to be escaped.
 *
 * ```
 * HttpUrl url = HttpUrl.parse("http://who-let-the-dogs.out").newBuilder()
 *     .addPathSegment("_Who?_")
 *     .query("_Who?_")
 *     .fragment("_Who?_")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * This prints:
 *
 * ```
 * http://who-let-the-dogs.out/_Who%3F_?_Who?_#_Who?_
 * ```
 *
 * When parsing URLs that lack percent encoding where it is required, this class will percent encode
 * the offending characters.
 *
 * ### IDNA Mapping and Punycode encoding
 *
 * Hostnames have different requirements and use a different encoding scheme. It consists of IDNA
 * mapping and Punycode encoding.
 *
 * In order to avoid confusion and discourage phishing attacks, [IDNA Mapping][idna] transforms
 * names to avoid confusing characters. This includes basic case folding: transforming shouting
 * `SQUARE.COM` into cool and casual `square.com`. It also handles more exotic characters. For
 * example, the Unicode trademark sign (™) could be confused for the letters "TM" in
 * `http://ho™mail.com`. To mitigate this, the single character (™) maps to the string (tm). There
 * is similar policy for all of the 1.1 million Unicode code points. Note that some code points such
 * as "\ud83c\udf69" are not mapped and cannot be used in a hostname.
 *
 * [Punycode](http://ietf.org/rfc/rfc3492.txt) converts a Unicode string to an ASCII string to make
 * international domain names work everywhere. For example, "σ" encodes as "xn--4xa". The encoded
 * string is not human readable, but can be used with classes like [InetAddress] to establish
 * connections.
 *
 * ## Why another URL model?
 *
 * Java includes both [java.net.URL][URL] and [java.net.URI][URI]. We offer a new URL
 * model to address problems that the others don't.
 *
 * ### Different URLs should be different
 *
 * Although they have different content, `java.net.URL` considers the following two URLs
 * equal, and the [equals()][Object.equals] method between them returns true:
 *
 *  * http://square.github.io/
 *
 *  * http://google.github.io/
 *
 * This is because those two hosts share the same IP address. This is an old, bad design decision
 * that makes `java.net.URL` unusable for many things. It shouldn't be used as a [Map] key or in a
 * [Set]. Doing so is both inefficient because equality may require a DNS lookup, and incorrect
 * because unequal URLs may be equal because of how they are hosted.
 *
 * ### Equal URLs should be equal
 *
 * These two URLs are semantically identical, but `java.net.URI` disagrees:
 *
 *  * http://host:80/
 *
 *  * http://host
 *
 * Both the unnecessary port specification (`:80`) and the absent trailing slash (`/`) cause URI to
 * bucket the two URLs separately. This harms URI's usefulness in collections. Any application that
 * stores information-per-URL will need to either canonicalize manually, or suffer unnecessary
 * redundancy for such URLs.
 *
 * Because they don't attempt canonical form, these classes are surprisingly difficult to use
 * securely. Suppose you're building a webservice that checks that incoming paths are prefixed
 * "/static/images/" before serving the corresponding assets from the filesystem.
 *
 * ```
 * String attack = "http://example.com/static/images/../../../../../etc/passwd";
 * System.out.println(new URL(attack).getPath());
 * System.out.println(new URI(attack).getPath());
 * System.out.println(HttpUrl.parse(attack).encodedPath());
 * ```
 *
 * By canonicalizing the input paths, they are complicit in directory traversal attacks. Code that
 * checks only the path prefix may suffer!
 *
 * ```
 * /static/images/../../../../../etc/passwd
 * /static/images/../../../../../etc/passwd
 * /etc/passwd
 * ```
 *
 * ### If it works on the web, it should work in your application
 *
 * The `java.net.URI` class is strict around what URLs it accepts. It rejects URLs like
 * `http://example.com/abc|def` because the `|` character is unsupported. This class is more
 * forgiving: it will automatically percent-encode the `|'` yielding `http://example.com/abc%7Cdef`.
 * This kind behavior is consistent with web browsers. `HttpUrl` prefers consistency with major web
 * browsers over consistency with obsolete specifications.
 *
 * ### Paths and Queries should decompose
 *
 * Neither of the built-in URL models offer direct access to path segments or query parameters.
 * Manually using `StringBuilder` to assemble these components is cumbersome: do '+' characters get
 * silently replaced with spaces? If a query parameter contains a '&amp;', does that get escaped?
 * By offering methods to read and write individual query parameters directly, application
 * developers are saved from the hassles of encoding and decoding.
 *
 * ### Plus a modern API
 *
 * The URL (JDK1.0) and URI (Java 1.4) classes predate builders and instead use telescoping
 * constructors. For example, there's no API to compose a URI with a custom port without also
 * providing a query and fragment.
 *
 * Instances of [HttpUrl] are well-formed and always have a scheme, host, and path. With
 * `java.net.URL` it's possible to create an awkward URL like `http:/` with scheme and path but no
 * hostname. Building APIs that consume such malformed values is difficult!
 *
 * This class has a modern API. It avoids punitive checked exceptions: [get] throws
 * [IllegalArgumentException] on invalid input or [parse] returns null if the input is an invalid
 * URL. You can even be explicit about whether each component has been encoded already.
 *
 * [idna]: http://www.unicode.org/reports/tr46/#ToASCII
 */
class HttpUrl internal constructor(builder: Builder) {

  /** Either "http" or "https".  */
  internal val scheme: String = builder.scheme ?: throw IllegalStateException("scheme == null")

  /** Decoded username.  */
  private val username: String = percentDecode(builder.encodedUsername, false)

  /** Decoded password.  */
  private val password: String = percentDecode(builder.encodedPassword, false)

  /** Canonical hostname.  */
  internal val host: String = builder.host ?: throw IllegalStateException("host == null")

  /** Either 80, 443 or a user-specified port. In range [1..65535].  */
  internal val port: Int = builder.effectivePort()

  /**
   * A list of canonical path segments. This list always contains at least one element, which may be
   * the empty string. Each segment is formatted with a leading '/', so if path segments were ["a",
   * "b", ""], then the encoded path would be "/a/b/".
   */
  @Suppress("UNCHECKED_CAST")
  private val pathSegments: List<String> =
      percentDecode(builder.encodedPathSegments, false) as List<String>

  /**
   * Alternating, decoded query names and values, or null for no query. Names may be empty or
   * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
   * empty, or non-empty.
   */
  private val queryNamesAndValues: List<String?>? =
      builder.encodedQueryNamesAndValues?.let { percentDecode(it, true) }

  /** Decoded fragment.  */
  private val fragment: String? = builder.encodedFragment?.let { percentDecode(it, false) }

  /** Canonical URL.  */
  private val url: String = builder.toString()

  val isHttps: Boolean = scheme == "https"

  /** Returns this URL as a [java.net.URL][URL].  */
  fun url(): URL {
    try {
      return URL(url)
    } catch (e: MalformedURLException) {
      throw RuntimeException(e) // Unexpected!
    }
  }

  /**
   * Returns this URL as a [java.net.URI][URI]. Because `URI` is more strict than this class, the
   * returned URI may be semantically different from this URL:
   *
   *  * Characters forbidden by URI like `[` and `|` will be escaped.
   *
   *  * Invalid percent-encoded sequences like `%xx` will be encoded like `%25xx`.
   *
   *  * Whitespace and control characters in the fragment will be stripped.
   *
   * These differences may have a significant consequence when the URI is interpreted by a
   * web server. For this reason the [URI class][URI] and this method should be avoided.
   */
  fun uri(): URI {
    val uri = newBuilder().reencodeForUri().toString()
    try {
      return URI(uri)
    } catch (e: URISyntaxException) {
      // Unlikely edge case: the URI has a forbidden character in the fragment. Strip it & retry.
      try {
        val stripped = uri.replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]"), "")
        return URI.create(stripped)
      } catch (e1: Exception) {
        throw RuntimeException(e) // Unexpected!
      }
    }
  }

  /** Returns either "http" or "https".  */
  fun scheme(): String = scheme

  /**
   * Returns the username, or an empty string if none is set.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedUsername()`</th></tr>
   * <tr><td>`http://host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username@host/`</td><td>`"username"`</td></tr>
   * <tr><td>`http://username:password@host/`</td><td>`"username"`</td></tr>
   * <tr><td>`http://a%20b:c%20d@host/`</td><td>`"a%20b"`</td></tr>
   * </table>
   */
  fun encodedUsername(): String {
    if (username.isEmpty()) return ""
    val usernameStart = scheme.length + 3 // "://".length() == 3.
    val usernameEnd = delimiterOffset(url, usernameStart, url.length, ":@")
    return url.substring(usernameStart, usernameEnd)
  }

  /**
   * Returns the decoded username, or an empty string if none is present.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`username()`</th></tr>
   * <tr><td>`http://host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username@host/`</td><td>`"username"`</td></tr>
   * <tr><td>`http://username:password@host/`</td><td>`"username"`</td></tr>
   * <tr><td>`http://a%20b:c%20d@host/`</td><td>`"a b"`</td></tr>
   * </table>
   */
  fun username(): String = username

  /**
   * Returns the password, or an empty string if none is set.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedPassword()`</th></tr>
   * <tr><td>`http://host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username@host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username:password@host/`</td><td>`"password"`</td></tr>
   * <tr><td>`http://a%20b:c%20d@host/`</td><td>`"c%20d"`</td></tr>
   * </table>
   */
  fun encodedPassword(): String {
    if (password.isEmpty()) return ""
    val passwordStart = url.indexOf(':', scheme.length + 3) + 1
    val passwordEnd = url.indexOf('@')
    return url.substring(passwordStart, passwordEnd)
  }

  /**
   * Returns the decoded password, or an empty string if none is present.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`password()`</th></tr>
   * <tr><td>`http://host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username@host/`</td><td>`""`</td></tr>
   * <tr><td>`http://username:password@host/`</td><td>`"password"`</td></tr>
   * <tr><td>`http://a%20b:c%20d@host/`</td><td>`"c d"`</td></tr>
   * </table>
   */
  fun password(): String = password

  /**
   * Returns the host address suitable for use with [InetAddress.getAllByName]. May be:
   *
   *  * A regular host name, like `android.com`.
   *
   *  * An IPv4 address, like `127.0.0.1`.
   *
   *  * An IPv6 address, like `::1`. Note that there are no square braces.
   *
   *  * An encoded IDN, like `xn--n3h.net`.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`host()`</th></tr>
   * <tr><td>`http://android.com/`</td><td>`"android.com"`</td></tr>
   * <tr><td>`http://127.0.0.1/`</td><td>`"127.0.0.1"`</td></tr>
   * <tr><td>`http://[::1]/`</td><td>`"::1"`</td></tr>
   * <tr><td>`http://xn--n3h.net/`</td><td>`"xn--n3h.net"`</td></tr>
   * </table>
   */
  fun host(): String = host

  /**
   * Returns the explicitly-specified port if one was provided, or the default port for this URL's
   * scheme. For example, this returns 8443 for `https://square.com:8443/` and 443 for
   * `https://square.com/`. The result is in `[1..65535]`.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`port()`</th></tr>
   * <tr><td>`http://host/`</td><td>`80`</td></tr>
   * <tr><td>`http://host:8000/`</td><td>`8000`</td></tr>
   * <tr><td>`https://host/`</td><td>`443`</td></tr>
   * </table>
   */
  fun port(): Int = port

  /**
   * Returns the number of segments in this URL's path. This is also the number of slashes in the
   * URL's path, like 3 in `http://host/a/b/c`. This is always at least 1.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`pathSize()`</th></tr>
   * <tr><td>`http://host/`</td><td>`1`</td></tr>
   * <tr><td>`http://host/a/b/c`</td><td>`3`</td></tr>
   * <tr><td>`http://host/a/b/c/`</td><td>`4`</td></tr>
   * </table>
   */
  fun pathSize(): Int = pathSegments.size

  /**
   * Returns the entire path of this URL encoded for use in HTTP resource resolution. The returned
   * path will start with `"/"`.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedPath()`</th></tr>
   * <tr><td>`http://host/`</td><td>`"/"`</td></tr>
   * <tr><td>`http://host/a/b/c`</td><td>`"/a/b/c"`</td></tr>
   * <tr><td>`http://host/a/b%20c/d`</td><td>`"/a/b%20c/d"`</td></tr>
   * </table>
   */
  fun encodedPath(): String {
    val pathStart = url.indexOf('/', scheme.length + 3) // "://".length() == 3.
    val pathEnd = delimiterOffset(url, pathStart, url.length, "?#")
    return url.substring(pathStart, pathEnd)
  }

  /**
   * Returns a list of encoded path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`.
   * This list is never empty though it may contain a single empty string.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedPathSegments()`</th></tr>
   * <tr><td>`http://host/`</td><td>`[""]`</td></tr>
   * <tr><td>`http://host/a/b/c`</td><td>`["a", "b", "c"]`</td></tr>
   * <tr><td>`http://host/a/b%20c/d`</td><td>`["a", "b%20c", "d"]`</td></tr>
   * </table>
   */
  fun encodedPathSegments(): List<String> {
    val pathStart = url.indexOf('/', scheme.length + 3)
    val pathEnd = delimiterOffset(url, pathStart, url.length, "?#")
    val result = ArrayList<String>()
    var i = pathStart
    while (i < pathEnd) {
      i++ // Skip the '/'.
      val segmentEnd = delimiterOffset(url, i, pathEnd, '/')
      result.add(url.substring(i, segmentEnd))
      i = segmentEnd
    }
    return result
  }

  /**
   * Returns a list of path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This
   * list is never empty though it may contain a single empty string.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`pathSegments()`</th></tr>
   * <tr><td>`http://host/`</td><td>`[""]`</td></tr>
   * <tr><td>`http://host/a/b/c"`</td><td>`["a", "b", "c"]`</td></tr>
   * <tr><td>`http://host/a/b%20c/d"`</td><td>`["a", "b c", "d"]`</td></tr>
   * </table>
   */
  fun pathSegments(): List<String> = pathSegments

  /**
   * Returns the query of this URL, encoded for use in HTTP resource resolution. The returned string
   * may be null (for URLs with no query), empty (for URLs with an empty query) or non-empty (all
   * other URLs).
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedQuery()`</th></tr>
   * <tr><td>`http://host/`</td><td>null</td></tr>
   * <tr><td>`http://host/?`</td><td>`""`</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`"a=apple&k=key+lime"`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`"a=apple&a=apricot"`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`"a=apple&b"`</td></tr>
   * </table>
   */
  fun encodedQuery(): String? {
    if (queryNamesAndValues == null) return null // No query.
    val queryStart = url.indexOf('?') + 1
    val queryEnd = delimiterOffset(url, queryStart, url.length, '#')
    return url.substring(queryStart, queryEnd)
  }

  /**
   * Returns this URL's query, like `"abc"` for `http://host/?abc`. Most callers should
   * prefer [queryParameterName] and [queryParameterValue] because these methods offer direct access
   * to individual query parameters.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`query()`</th></tr>
   * <tr><td>`http://host/`</td><td>null</td></tr>
   * <tr><td>`http://host/?`</td><td>`""`</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`"a=apple&k=key
   * lime"`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`"a=apple&a=apricot"`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`"a=apple&b"`</td></tr>
   * </table>
   */
  fun query(): String? {
    if (queryNamesAndValues == null) return null // No query.
    val result = StringBuilder()
    namesAndValuesToQueryString(result, queryNamesAndValues)
    return result.toString()
  }

  /**
   * Returns the number of query parameters in this URL, like 2 for `http://host/?a=apple&b=banana`.
   * If this URL has no query this returns 0. Otherwise it returns one more than the number of `"&"`
   * separators in the query.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`querySize()`</th></tr>
   * <tr><td>`http://host/`</td><td>`0`</td></tr>
   * <tr><td>`http://host/?`</td><td>`1`</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`2`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`2`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`2`</td></tr>
   * </table>
   */
  fun querySize(): Int {
    return if (queryNamesAndValues != null) queryNamesAndValues.size / 2 else 0
  }

  /**
   * Returns the first query parameter named `name` decoded using UTF-8, or null if there is
   * no such query parameter.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`queryParameter("a")`</th></tr>
   * <tr><td>`http://host/`</td><td>null</td></tr>
   * <tr><td>`http://host/?`</td><td>null</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`"apple"`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`"apple"`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`"apple"`</td></tr>
   * </table>
   */
  fun queryParameter(name: String): String? {
    if (queryNamesAndValues == null) return null
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        return queryNamesAndValues[i + 1]
      }
    }
    return null
  }

  /**
   * Returns the distinct query parameter names in this URL, like `["a", "b"]` for
   * `http://host/?a=apple&b=banana`. If this URL has no query this returns the empty set.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`queryParameterNames()`</th></tr>
   * <tr><td>`http://host/`</td><td>`[]`</td></tr>
   * <tr><td>`http://host/?`</td><td>`[""]`</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`["a", "k"]`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`["a"]`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`["a", "b"]`</td></tr>
   * </table>
   */
  fun queryParameterNames(): Set<String> {
    if (queryNamesAndValues == null) return emptySet()
    val result = LinkedHashSet<String>()
    for (i in 0 until queryNamesAndValues.size step 2) {
      result.add(queryNamesAndValues[i]!!)
    }
    return Collections.unmodifiableSet(result)
  }

  /**
   * Returns all values for the query parameter `name` ordered by their appearance in this
   * URL. For example this returns `["banana"]` for `queryParameterValue("b")` on
   * `http://host/?a=apple&b=banana`.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`queryParameterValues("a")`</th><th>`queryParameterValues("b")`</th></tr>
   * <tr><td>`http://host/`</td><td>`[]`</td><td>`[]`</td></tr>
   * <tr><td>`http://host/?`</td><td>`[]`</td><td>`[]`</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`["apple"]`</td><td>`[]`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`["apple", "apricot"]`</td><td>`[]`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`["apple"]`</td><td>`[null]`</td></tr>
   * </table>
   */
  fun queryParameterValues(name: String): List<String?> {
    if (queryNamesAndValues == null) return emptyList()
    val result = ArrayList<String?>()
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        result.add(queryNamesAndValues[i + 1])
      }
    }
    return Collections.unmodifiableList(result)
  }

  /**
   * Returns the name of the query parameter at `index`. For example this returns `"a"`
   * for `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if
   * `index` is not less than the [query size][querySize].
   *
   * <table summary="">
   * <tr><th>URL</th><th>`queryParameterName(0)`</th><th>`queryParameterName(1)`</th></tr>
   * <tr><td>`http://host/`</td><td>exception</td><td>exception</td></tr>
   * <tr><td>`http://host/?`</td><td>`""`</td><td>exception</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`"a"`</td><td>`"k"`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`"a"`</td><td>`"a"`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`"a"`</td><td>`"b"`</td></tr>
   * </table>
   */
  fun queryParameterName(index: Int): String {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2]!!
  }

  /**
   * Returns the value of the query parameter at `index`. For example this returns `"apple"` for
   * `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if `index` is not less
   * than the [query size][querySize].
   *
   * <table summary="">
   * <tr><th>URL</th><th>`queryParameterValue(0)`</th><th>`queryParameterValue(1)`</th></tr>
   * <tr><td>`http://host/`</td><td>exception</td><td>exception</td></tr>
   * <tr><td>`http://host/?`</td><td>null</td><td>exception</td></tr>
   * <tr><td>`http://host/?a=apple&k=key+lime`</td><td>`"apple"`</td><td>`"key lime"`</td></tr>
   * <tr><td>`http://host/?a=apple&a=apricot`</td><td>`"apple"`</td><td>`"apricot"`</td></tr>
   * <tr><td>`http://host/?a=apple&b`</td><td>`"apple"`</td><td>null</td></tr>
   * </table>
   */
  fun queryParameterValue(index: Int): String? {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2 + 1]
  }

  /**
   * Returns this URL's encoded fragment, like `"abc"` for `http://host/#abc`. This returns null if
   * the URL has no fragment.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`encodedFragment()`</th></tr>
   * <tr><td>`http://host/`</td><td>null</td></tr>
   * <tr><td>`http://host/#`</td><td>`""`</td></tr>
   * <tr><td>`http://host/#abc`</td><td>`"abc"`</td></tr>
   * <tr><td>`http://host/#abc|def`</td><td>`"abc|def"`</td></tr>
   * </table>
   */
  fun encodedFragment(): String? {
    if (fragment == null) return null
    val fragmentStart = url.indexOf('#') + 1
    return url.substring(fragmentStart)
  }

  /**
   * Returns this URL's fragment, like `"abc"` for `http://host/#abc`. This returns null
   * if the URL has no fragment.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`fragment()`</th></tr>
   * <tr><td>`http://host/`</td><td>null</td></tr>
   * <tr><td>`http://host/#`</td><td>`""`</td></tr>
   * <tr><td>`http://host/#abc`</td><td>`"abc"`</td></tr>
   * <tr><td>`http://host/#abc|def`</td><td>`"abc|def"`</td></tr>
   * </table>
   */
  fun fragment(): String? = fragment

  /**
   * Returns a string with containing this URL with its username, password, query, and fragment
   * stripped, and its path replaced with `/...`. For example, redacting
   * `http://username:password@example.com/path` returns `http://example.com/...`.
   */
  fun redact(): String {
    return newBuilder("/...")!!
        .username("")
        .password("")
        .build()
        .toString()
  }

  /**
   * Returns the URL that would be retrieved by following `link` from this URL, or null if the
   * resulting URL is not well-formed.
   */
  fun resolve(link: String): HttpUrl? = newBuilder(link)?.build()

  fun newBuilder(): Builder {
    val result = Builder()
    result.scheme = scheme
    result.encodedUsername = encodedUsername()
    result.encodedPassword = encodedPassword()
    result.host = host
    // If we're set to a default port, unset it in case of a scheme change.
    result.port = if (port != defaultPort(scheme)) port else -1
    result.encodedPathSegments.clear()
    result.encodedPathSegments.addAll(encodedPathSegments())
    result.encodedQuery(encodedQuery())
    result.encodedFragment = encodedFragment()
    return result
  }

  /**
   * Returns a builder for the URL that would be retrieved by following `link` from this URL,
   * or null if the resulting URL is not well-formed.
   */
  fun newBuilder(link: String): Builder? {
    try {
      return Builder().parse(this, link)
    } catch (ignored: IllegalArgumentException) {
      return null
    }
  }

  override fun equals(other: Any?): Boolean {
    return other is HttpUrl && other.url == url
  }

  override fun hashCode(): Int = url.hashCode()

  override fun toString(): String = url

  /**
   * Returns the domain name of this URL's [host] that is one level beneath the public suffix by
   * consulting the [public suffix list](https://publicsuffix.org). Returns null if this URL's
   * [host] is an IP address or is considered a public suffix by the public suffix list.
   *
   * In general this method **should not** be used to test whether a domain is valid or routable.
   * Instead, DNS is the recommended source for that information.
   *
   * <table summary="">
   * <tr><th>URL</th><th>`topPrivateDomain()`</th></tr>
   * <tr><td>`http://google.com`</td><td>`"google.com"`</td></tr>
   * <tr><td>`http://adwords.google.co.uk`</td><td>`"google.co.uk"`</td></tr>
   * <tr><td>`http://square`</td><td>null</td></tr>
   * <tr><td>`http://co.uk`</td><td>null</td></tr>
   * <tr><td>`http://localhost`</td><td>null</td></tr>
   * <tr><td>`http://127.0.0.1`</td><td>null</td></tr>
   * </table>
   */
  fun topPrivateDomain(): String? {
    if (verifyAsIpAddress(host)) {
      return null
    } else {
      return PublicSuffixDatabase.get().getEffectiveTldPlusOne(host)
    }
  }

  class Builder {
    internal var scheme: String? = null
    internal var encodedUsername = ""
    internal var encodedPassword = ""
    internal var host: String? = null
    internal var port = -1
    internal val encodedPathSegments = mutableListOf<String>()
    internal var encodedQueryNamesAndValues: MutableList<String?>? = null
    internal var encodedFragment: String? = null

    init {
      encodedPathSegments.add("") // The default path is '/' which needs a trailing space.
    }

    fun scheme(scheme: String): Builder {
      when {
        scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
        scheme.equals("https", ignoreCase = true) -> this.scheme = "https"
        else -> throw IllegalArgumentException("unexpected scheme: $scheme")
      }
      return this
    }

    fun username(username: String): Builder {
      this.encodedUsername = canonicalize(username, USERNAME_ENCODE_SET, false, false, false, true)
      return this
    }

    fun encodedUsername(encodedUsername: String): Builder {
      this.encodedUsername = canonicalize(
          encodedUsername, USERNAME_ENCODE_SET, true, false, false, true)
      return this
    }

    fun password(password: String): Builder {
      this.encodedPassword = canonicalize(password, PASSWORD_ENCODE_SET, false, false, false, true)
      return this
    }

    fun encodedPassword(encodedPassword: String): Builder {
      this.encodedPassword = canonicalize(
          encodedPassword, PASSWORD_ENCODE_SET, true, false, false, true)
      return this
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     * address.
     */
    fun host(host: String): Builder {
      val encoded = canonicalizeHost(host, 0, host.length) ?: throw IllegalArgumentException(
          "unexpected host: $host")
      this.host = encoded
      return this
    }

    fun port(port: Int): Builder {
      if (port <= 0 || port > 65535) throw IllegalArgumentException("unexpected port: $port")
      this.port = port
      return this
    }

    internal fun effectivePort(): Int {
      return if (port != -1) port else defaultPort(scheme!!)
    }

    fun addPathSegment(pathSegment: String): Builder {
      push(pathSegment, 0, pathSegment.length, false, false)
      return this
    }

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
     * starts with a slash, the resulting URL will have empty path segment.
     */
    fun addPathSegments(pathSegments: String): Builder {
      return addPathSegments(pathSegments, false)
    }

    fun addEncodedPathSegment(encodedPathSegment: String): Builder {
      push(encodedPathSegment, 0, encodedPathSegment.length, false, true)
      return this
    }

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
     * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
     */
    fun addEncodedPathSegments(encodedPathSegments: String): Builder {
      return addPathSegments(encodedPathSegments, true)
    }

    private fun addPathSegments(pathSegments: String, alreadyEncoded: Boolean): Builder {
      var offset = 0
      do {
        val segmentEnd = delimiterOffset(pathSegments, offset, pathSegments.length, "/\\")
        val addTrailingSlash = segmentEnd < pathSegments.length
        push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded)
        offset = segmentEnd + 1
      } while (offset <= pathSegments.length)
      return this
    }

    fun setPathSegment(index: Int, pathSegment: String): Builder {
      val canonicalPathSegment = canonicalize(pathSegment, 0, pathSegment.length,
          PATH_SEGMENT_ENCODE_SET, false, false, false, true, null)
      if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
        throw IllegalArgumentException("unexpected path segment: $pathSegment")
      }
      encodedPathSegments[index] = canonicalPathSegment
      return this
    }

    fun setEncodedPathSegment(index: Int, encodedPathSegment: String): Builder {
      val canonicalPathSegment = canonicalize(encodedPathSegment, 0, encodedPathSegment.length,
          PATH_SEGMENT_ENCODE_SET, true, false, false, true, null)
      encodedPathSegments[index] = canonicalPathSegment
      if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
        throw IllegalArgumentException("unexpected path segment: $encodedPathSegment")
      }
      return this
    }

    fun removePathSegment(index: Int): Builder {
      encodedPathSegments.removeAt(index)
      if (encodedPathSegments.isEmpty()) {
        encodedPathSegments.add("") // Always leave at least one '/'.
      }
      return this
    }

    fun encodedPath(encodedPath: String): Builder {
      if (!encodedPath.startsWith("/")) {
        throw IllegalArgumentException("unexpected encodedPath: $encodedPath")
      }
      resolvePath(encodedPath, 0, encodedPath.length)
      return this
    }

    fun query(query: String?): Builder {
      this.encodedQueryNamesAndValues = if (query != null) {
        queryStringToNamesAndValues(canonicalize(query, QUERY_ENCODE_SET, false, false, true, true))
      } else {
        null
      }
      return this
    }

    fun encodedQuery(encodedQuery: String?): Builder {
      this.encodedQueryNamesAndValues = if (encodedQuery != null) {
        queryStringToNamesAndValues(
            canonicalize(encodedQuery, QUERY_ENCODE_SET, true, false, true, true))
      } else {
        null
      }
      return this
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string.  */
    fun addQueryParameter(name: String, value: String?): Builder {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = ArrayList()
      encodedQueryNamesAndValues!!.add(
          canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, false, true, true))
      encodedQueryNamesAndValues!!.add(if (value != null) {
        canonicalize(value, QUERY_COMPONENT_ENCODE_SET, false, false, true, true)
      } else {
        null
      })
      return this
    }

    /** Adds the pre-encoded query parameter to this URL's query string.  */
    fun addEncodedQueryParameter(encodedName: String, encodedValue: String?): Builder {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = ArrayList()
      encodedQueryNamesAndValues!!.add(
          canonicalize(encodedName, QUERY_COMPONENT_REENCODE_SET, true, false, true, true))
      encodedQueryNamesAndValues!!.add(if (encodedValue != null) {
        canonicalize(encodedValue, QUERY_COMPONENT_REENCODE_SET, true, false, true, true)
      } else {
        null
      })
      return this
    }

    fun setQueryParameter(name: String, value: String?): Builder {
      removeAllQueryParameters(name)
      addQueryParameter(name, value)
      return this
    }

    fun setEncodedQueryParameter(encodedName: String, encodedValue: String?): Builder {
      removeAllEncodedQueryParameters(encodedName)
      addEncodedQueryParameter(encodedName, encodedValue)
      return this
    }

    fun removeAllQueryParameters(name: String): Builder {
      if (encodedQueryNamesAndValues == null) return this
      val nameToRemove = canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, false, true, true)
      removeAllCanonicalQueryParameters(nameToRemove)
      return this
    }

    fun removeAllEncodedQueryParameters(encodedName: String): Builder {
      if (encodedQueryNamesAndValues == null) return this
      removeAllCanonicalQueryParameters(
          canonicalize(encodedName, QUERY_COMPONENT_REENCODE_SET, true, false, true, true))
      return this
    }

    private fun removeAllCanonicalQueryParameters(canonicalName: String) {
      var i = encodedQueryNamesAndValues!!.size - 2
      while (i >= 0) {
        if (canonicalName == encodedQueryNamesAndValues!![i]) {
          encodedQueryNamesAndValues!!.removeAt(i + 1)
          encodedQueryNamesAndValues!!.removeAt(i)
          if (encodedQueryNamesAndValues!!.isEmpty()) {
            encodedQueryNamesAndValues = null
            return
          }
        }
        i -= 2
      }
    }

    fun fragment(fragment: String?): Builder {
      this.encodedFragment = if (fragment != null) {
        canonicalize(fragment, FRAGMENT_ENCODE_SET, false, false, false, false)
      } else {
        null
      }
      return this
    }

    fun encodedFragment(encodedFragment: String?): Builder {
      this.encodedFragment = if (encodedFragment != null) {
        canonicalize(encodedFragment, FRAGMENT_ENCODE_SET, true, false, false, false)
      } else {
        null
      }
      return this
    }

    /**
     * Re-encodes the components of this URL so that it satisfies (obsolete) RFC 2396, which is
     * particularly strict for certain components.
     */
    internal fun reencodeForUri(): Builder {
      for (i in 0 until encodedPathSegments.size) {
        val pathSegment = encodedPathSegments[i]
        encodedPathSegments[i] =
            canonicalize(pathSegment, PATH_SEGMENT_ENCODE_SET_URI, true, true, false, true)
      }
      if (encodedQueryNamesAndValues != null) {
        for (i in 0 until encodedQueryNamesAndValues!!.size) {
          val component = encodedQueryNamesAndValues!![i]
          if (component != null) {
            encodedQueryNamesAndValues!![i] =
                canonicalize(component, QUERY_COMPONENT_ENCODE_SET_URI, true, true, true, true)
          }
        }
      }
      if (encodedFragment != null) {
        encodedFragment = canonicalize(
            encodedFragment!!, FRAGMENT_ENCODE_SET_URI, true, true, false, false)
      }
      return this
    }

    fun build(): HttpUrl = HttpUrl(this)

    override fun toString(): String {
      val result = StringBuilder()
      if (scheme != null) {
        result.append(scheme)
        result.append("://")
      } else {
        result.append("//")
      }

      if (!encodedUsername.isEmpty() || !encodedPassword.isEmpty()) {
        result.append(encodedUsername)
        if (!encodedPassword.isEmpty()) {
          result.append(':')
          result.append(encodedPassword)
        }
        result.append('@')
      }

      if (host != null) {
        if (host!!.indexOf(':') != -1) {
          // Host is an IPv6 address.
          result.append('[')
          result.append(host)
          result.append(']')
        } else {
          result.append(host)
        }
      }

      if (port != -1 || scheme != null) {
        val effectivePort = effectivePort()
        if (scheme == null || effectivePort != defaultPort(scheme!!)) {
          result.append(':')
          result.append(effectivePort)
        }
      }

      pathSegmentsToString(result, encodedPathSegments)

      if (encodedQueryNamesAndValues != null) {
        result.append('?')
        namesAndValuesToQueryString(result, encodedQueryNamesAndValues!!)
      }

      if (encodedFragment != null) {
        result.append('#')
        result.append(encodedFragment)
      }

      return result.toString()
    }

    internal fun parse(base: HttpUrl?, input: String): Builder {
      var pos = skipLeadingAsciiWhitespace(input, 0, input.length)
      val limit = skipTrailingAsciiWhitespace(input, pos, input.length)

      // Scheme.
      val schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit)
      if (schemeDelimiterOffset != -1) {
        if (input.regionMatches(pos, "https:", 0, 6, ignoreCase = true)) {
          this.scheme = "https"
          pos += "https:".length
        } else if (input.regionMatches(pos, "http:", 0, 5, ignoreCase = true)) {
          this.scheme = "http"
          pos += "http:".length
        } else {
          throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '"
              + input.substring(0, schemeDelimiterOffset) + "'")
        }
      } else if (base != null) {
        this.scheme = base.scheme
      } else {
        throw IllegalArgumentException(
            "Expected URL scheme 'http' or 'https' but no colon was found")
      }

      // Authority.
      var hasUsername = false
      var hasPassword = false
      val slashCount = slashCount(input, pos, limit)
      if (slashCount >= 2 || base == null || base.scheme != this.scheme) {
        // Read an authority if either:
        //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
        //  * The input scheme exists and is different from the base URL's scheme.
        //
        // The structure of an authority is:
        //   username:password@host:port
        //
        // Username, password and port are optional.
        //   [username[:password]@]host[:port]
        pos += slashCount
        authority@ while (true) {
          val componentDelimiterOffset = delimiterOffset(input, pos, limit, "@/\\?#")
          val c = if (componentDelimiterOffset != limit) {
            input[componentDelimiterOffset].toInt()
          } else {
            -1
          }
          when (c) {
            '@'.toInt() -> {
              // User info precedes.
              if (!hasPassword) {
                val passwordColonOffset = delimiterOffset(
                    input, pos, componentDelimiterOffset, ':')
                val canonicalUsername = canonicalize(input, pos, passwordColonOffset,
                    USERNAME_ENCODE_SET, true, false, false, true, null)
                this.encodedUsername = if (hasUsername) {
                  this.encodedUsername + "%40" + canonicalUsername
                } else {
                  canonicalUsername
                }
                if (passwordColonOffset != componentDelimiterOffset) {
                  hasPassword = true
                  this.encodedPassword = canonicalize(input, passwordColonOffset + 1,
                      componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false, false, true, null)
                }
                hasUsername = true
              } else {
                this.encodedPassword = this.encodedPassword + "%40" + canonicalize(input, pos,
                    componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false, false, true, null)
              }
              pos = componentDelimiterOffset + 1
            }

            -1, '/'.toInt(), '\\'.toInt(), '?'.toInt(), '#'.toInt() -> {
              // Host info precedes.
              val portColonOffset = portColonOffset(input, pos, componentDelimiterOffset)
              if (portColonOffset + 1 < componentDelimiterOffset) {
                host = canonicalizeHost(input, pos, portColonOffset)
                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset)
                require(port != -1) {
                  "Invalid URL port: \"${input.substring(portColonOffset + 1,
                      componentDelimiterOffset)}\""
                }
              } else {
                host = canonicalizeHost(input, pos, portColonOffset)
                port = defaultPort(scheme!!)
              }
              require(host != null) {
                "$INVALID_HOST: \"${input.substring(pos, portColonOffset)}\""
              }
              pos = componentDelimiterOffset
              break@authority
            }
          }
        }
      } else {
        // This is a relative link. Copy over all authority components. Also maybe the path & query.
        this.encodedUsername = base.encodedUsername()
        this.encodedPassword = base.encodedPassword()
        this.host = base.host
        this.port = base.port
        this.encodedPathSegments.clear()
        this.encodedPathSegments.addAll(base.encodedPathSegments())
        if (pos == limit || input[pos] == '#') {
          encodedQuery(base.encodedQuery())
        }
      }

      // Resolve the relative path.
      val pathDelimiterOffset = delimiterOffset(input, pos, limit, "?#")
      resolvePath(input, pos, pathDelimiterOffset)
      pos = pathDelimiterOffset

      // Query.
      if (pos < limit && input[pos] == '?') {
        val queryDelimiterOffset = delimiterOffset(input, pos, limit, '#')
        this.encodedQueryNamesAndValues = queryStringToNamesAndValues(canonicalize(
            input, pos + 1, queryDelimiterOffset, QUERY_ENCODE_SET, true, false, true, true, null))
        pos = queryDelimiterOffset
      }

      // Fragment.
      if (pos < limit && input[pos] == '#') {
        this.encodedFragment = canonicalize(
            input, pos + 1, limit, FRAGMENT_ENCODE_SET, true, false, false, false, null)
      }

      return this
    }

    private fun resolvePath(input: String, startPos: Int, limit: Int) {
      var pos = startPos
      // Read a delimiter.
      if (pos == limit) {
        // Empty path: keep the base path as-is.
        return
      }
      val c = input[pos]
      if (c == '/' || c == '\\') {
        // Absolute path: reset to the default "/".
        encodedPathSegments.clear()
        encodedPathSegments.add("")
        pos++
      } else {
        // Relative path: clear everything after the last '/'.
        encodedPathSegments[encodedPathSegments.size - 1] = ""
      }

      // Read path segments.
      var i = pos
      while (i < limit) {
        val pathSegmentDelimiterOffset = delimiterOffset(input, i, limit, "/\\")
        val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
        push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
        i = pathSegmentDelimiterOffset
        if (segmentHasTrailingSlash) i++
      }
    }

    /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment.  */
    private fun push(
      input: String, pos: Int, limit: Int, addTrailingSlash: Boolean,
      alreadyEncoded: Boolean
    ) {
      val segment = canonicalize(
          input, pos, limit, PATH_SEGMENT_ENCODE_SET, alreadyEncoded, false, false, true, null)
      if (isDot(segment)) {
        return  // Skip '.' path segments.
      }
      if (isDotDot(segment)) {
        pop()
        return
      }
      if (encodedPathSegments[encodedPathSegments.size - 1].isEmpty()) {
        encodedPathSegments[encodedPathSegments.size - 1] = segment
      } else {
        encodedPathSegments.add(segment)
      }
      if (addTrailingSlash) {
        encodedPathSegments.add("")
      }
    }

    private fun isDot(input: String): Boolean {
      return input == "." || input.equals("%2e", ignoreCase = true)
    }

    private fun isDotDot(input: String): Boolean {
      return (input == ".."
          || input.equals("%2e.", ignoreCase = true)
          || input.equals(".%2e", ignoreCase = true)
          || input.equals("%2e%2e", ignoreCase = true))
    }

    /**
     * Removes a path segment. When this method returns the last segment is always "", which means
     * the encoded path will have a trailing '/'.
     *
     * Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from ["a",
     * "b", "c", ""] to ["a", "b", ""].
     *
     * Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
     * to ["a", "b", ""].
     */
    private fun pop() {
      val removed = encodedPathSegments.removeAt(encodedPathSegments.size - 1)

      // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
      if (removed.isEmpty() && !encodedPathSegments.isEmpty()) {
        encodedPathSegments[encodedPathSegments.size - 1] = ""
      } else {
        encodedPathSegments.add("")
      }
    }

    companion object {
      internal const val INVALID_HOST = "Invalid URL host"

      /**
       * Returns the index of the ':' in `input` that is after scheme characters. Returns -1 if
       * `input` does not have a scheme that starts at `pos`.
       */
      @JvmStatic
      private fun schemeDelimiterOffset(input: String, pos: Int, limit: Int): Int {
        if (limit - pos < 2) return -1

        val c0 = input[pos]
        if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1 // Not a scheme start char.

        for (i in pos + 1 until limit) {
          val c = input[i]

          if (c >= 'a' && c <= 'z'
              || c >= 'A' && c <= 'Z'
              || c >= '0' && c <= '9'
              || c == '+'
              || c == '-'
              || c == '.') {
            continue // Scheme character. Keep going.
          } else if (c == ':') {
            return i // Scheme prefix!
          } else {
            return -1 // Non-scheme character before the first ':'.
          }
        }

        return -1 // No ':'; doesn't start with a scheme.
      }

      /** Returns the number of '/' and '\' slashes in `input`, starting at `pos`.  */
      @JvmStatic
      private fun slashCount(input: String, pos: Int, limit: Int): Int {
        var slashCount = 0
        for (i in pos until limit) {
          val c = input[i]
          if (c == '\\' || c == '/') {
            slashCount++
          } else {
            break
          }
        }
        return slashCount
      }

      /** Finds the first ':' in `input`, skipping characters between square braces "[...]".  */
      @JvmStatic
      private fun portColonOffset(input: String, pos: Int, limit: Int): Int {
        var i = pos
        while (i < limit) {
          when (input[i]) {
            '[' -> {
              while (++i < limit) {
                if (input[i] == ']') break
              }
            }
            ':' -> return i
          }
          i++
        }
        return limit // No colon.
      }

      @JvmStatic
      private fun canonicalizeHost(input: String, pos: Int, limit: Int): String? {
        // Start by percent decoding the host. The WHATWG spec suggests doing this only after we've
        // checked for IPv6 square braces. But Chrome does it first, and that's more lenient.
        val percentDecoded = percentDecode(input, pos, limit, false)
        return Util.canonicalizeHost(percentDecoded)
      }

      @JvmStatic
      private fun parsePort(input: String, pos: Int, limit: Int): Int {
        try {
          // Canonicalize the port string to skip '\n' etc.
          val portString = canonicalize(input, pos, limit, "", false, false, false, true, null)
          val i = Integer.parseInt(portString)
          return if (i > 0 && i <= 65535) i else -1
        } catch (e: NumberFormatException) {
          return -1 // Invalid port.
        }

      }
    }
  }

  private fun percentDecode(list: List<String?>, plusIsSpace: Boolean): List<String?> {
    val size = list.size
    val result = ArrayList<String?>(size)
    for (i in 0 until size) {
      val s = list[i]
      result.add(if (s != null) percentDecode(s, plusIsSpace) else null)
    }
    return Collections.unmodifiableList(result)
  }

  companion object {
    @JvmStatic
    private val HEX_DIGITS =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
    internal const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
    internal const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
    internal const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
    internal const val PATH_SEGMENT_ENCODE_SET_URI = "[]"
    internal const val QUERY_ENCODE_SET = " \"'<>#"
    internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="
    internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
    internal const val QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}"
    internal const val FORM_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#&!$(),~"
    internal const val FRAGMENT_ENCODE_SET = ""
    internal const val FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}"

    /** Returns 80 if `scheme.equals("http")`, 443 if `scheme.equals("https")` and -1 otherwise. */
    @JvmStatic
    fun defaultPort(scheme: String): Int {
      return when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
      }
    }

    @JvmStatic
    internal fun pathSegmentsToString(out: StringBuilder, pathSegments: List<String>) {
      for (i in 0 until pathSegments.size) {
        out.append('/')
        out.append(pathSegments[i])
      }
    }

    @JvmStatic
    internal fun namesAndValuesToQueryString(out: StringBuilder, namesAndValues: List<String?>) {
      for (i in 0 until namesAndValues.size step 2) {
        val name = namesAndValues[i]
        val value = namesAndValues[i + 1]
        if (i > 0) out.append('&')
        out.append(name)
        if (value != null) {
          out.append('=')
          out.append(value)
        }
      }
    }

    /**
     * Cuts `encodedQuery` up into alternating parameter names and values. This divides a query
     * string like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy",
     * null, "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
     */
    @JvmStatic
    internal fun queryStringToNamesAndValues(encodedQuery: String): MutableList<String?> {
      val result = mutableListOf<String?>()
      var pos = 0
      while (pos <= encodedQuery.length) {
        var ampersandOffset = encodedQuery.indexOf('&', pos)
        if (ampersandOffset == -1) ampersandOffset = encodedQuery.length

        val equalsOffset = encodedQuery.indexOf('=', pos)
        if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
          result.add(encodedQuery.substring(pos, ampersandOffset))
          result.add(null) // No value for this name.
        } else {
          result.add(encodedQuery.substring(pos, equalsOffset))
          result.add(encodedQuery.substring(equalsOffset + 1, ampersandOffset))
        }
        pos = ampersandOffset + 1
      }
      return result
    }

    /**
     * Returns a new `HttpUrl` representing `url` if it is a well-formed HTTP or HTTPS URL, or null
     * if it isn't.
     */
    @JvmStatic
    fun parse(url: String): HttpUrl? {
      try {
        return get(url)
      } catch (ignored: IllegalArgumentException) {
        return null
      }
    }

    /**
     * Returns a new `HttpUrl` representing `url`.
     *
     * @throws IllegalArgumentException If `url` is not a well-formed HTTP or HTTPS URL.
     */
    @JvmStatic
    operator fun get(url: String): HttpUrl = Builder().parse(null, url).build()

    /**
     * Returns an [HttpUrl] for `url` if its protocol is `http` or `https`, or
     * null if it has any other protocol.
     */
    @JvmStatic
    operator fun get(url: URL): HttpUrl? = parse(url.toString())

    @JvmStatic
    operator fun get(uri: URI): HttpUrl? = parse(uri.toString())

    @JvmStatic
    internal fun percentDecode(encoded: String, plusIsSpace: Boolean): String =
        percentDecode(encoded, 0, encoded.length, plusIsSpace)

    @JvmStatic
    internal fun percentDecode(
      encoded: String,
      pos: Int,
      limit: Int,
      plusIsSpace: Boolean
    ): String {
      for (i in pos until limit) {
        val c = encoded[i]
        if (c == '%' || c == '+' && plusIsSpace) {
          // Slow path: the character at i requires decoding!
          val out = Buffer()
          out.writeUtf8(encoded, pos, i)
          percentDecode(out, encoded, i, limit, plusIsSpace)
          return out.readUtf8()
        }
      }

      // Fast path: no characters in [pos..limit) required decoding.
      return encoded.substring(pos, limit)
    }

    @JvmStatic
    internal fun percentDecode(
      out: Buffer,
      encoded: String,
      pos: Int,
      limit: Int,
      plusIsSpace: Boolean
    ) {
      var codePoint: Int
      var i = pos
      while (i < limit) {
        codePoint = encoded.codePointAt(i)
        if (codePoint == '%'.toInt() && i + 2 < limit) {
          val d1 = decodeHexDigit(encoded[i + 1])
          val d2 = decodeHexDigit(encoded[i + 2])
          if (d1 != -1 && d2 != -1) {
            out.writeByte((d1 shl 4) + d2)
            i += 2
            i += Character.charCount(codePoint)
            continue
          }
        } else if (codePoint == '+'.toInt() && plusIsSpace) {
          out.writeByte(' '.toInt())
          i++
          continue
        }
        out.writeUtf8CodePoint(codePoint)
        i += Character.charCount(codePoint)
      }
    }

    @JvmStatic
    internal fun percentEncoded(encoded: String, pos: Int, limit: Int): Boolean {
      return (pos + 2 < limit
          && encoded[pos] == '%'
          && decodeHexDigit(encoded[pos + 1]) != -1
          && decodeHexDigit(encoded[pos + 2]) != -1)
    }

    /**
     * Returns a substring of `input` on the range `[pos..limit)` with the following
     * transformations:
     *
     *  * Tabs, newlines, form feeds and carriage returns are skipped.
     *
     *  * In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
     *
     *  * Characters in `encodeSet` are percent-encoded.
     *
     *  * Control characters and non-ASCII characters are percent-encoded.
     *
     *  * All other characters are copied without transformation.
     *
     * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
     * @param strict true to encode '%' if it is not the prefix of a valid percent encoding.
     * @param plusIsSpace true to encode '+' as "%2B" if it is not already encoded.
     * @param asciiOnly true to encode all non-ASCII codepoints.
     * @param charset which charset to use, null equals UTF-8.
     */
    @JvmStatic
    internal fun canonicalize(
      input: String, pos: Int, limit: Int, encodeSet: String,
      alreadyEncoded: Boolean, strict: Boolean, plusIsSpace: Boolean, asciiOnly: Boolean,
      charset: Charset?
    ): String {
      var codePoint: Int
      var i = pos
      while (i < limit) {
        codePoint = input.codePointAt(i)
        if (codePoint < 0x20
            || codePoint == 0x7f
            || codePoint >= 0x80 && asciiOnly
            || encodeSet.indexOf(codePoint.toChar()) != -1
            || codePoint == '%'.toInt() && (!alreadyEncoded || strict && !percentEncoded(input, i,
                limit))
            || codePoint == '+'.toInt() && plusIsSpace) {
          // Slow path: the character at i requires encoding!
          val out = Buffer()
          out.writeUtf8(input, pos, i)
          canonicalize(out, input, i, limit, encodeSet, alreadyEncoded, strict, plusIsSpace,
              asciiOnly, charset)
          return out.readUtf8()
        }
        i += Character.charCount(codePoint)
      }

      // Fast path: no characters in [pos..limit) required encoding.
      return input.substring(pos, limit)
    }

    @JvmStatic
    internal fun canonicalize(
      out: Buffer, input: String, pos: Int, limit: Int, encodeSet: String,
      alreadyEncoded: Boolean, strict: Boolean, plusIsSpace: Boolean, asciiOnly: Boolean,
      charset: Charset?
    ) {
      var encodedCharBuffer: Buffer? = null // Lazily allocated.
      var codePoint: Int
      var i = pos
      while (i < limit) {
        codePoint = input.codePointAt(i)
        if (alreadyEncoded && (codePoint == '\t'.toInt() || codePoint == '\n'.toInt() || codePoint == '\u000c'.toInt() || codePoint == '\r'.toInt())) {
          // Skip this character.
        } else if (codePoint == '+'.toInt() && plusIsSpace) {
          // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
          out.writeUtf8(if (alreadyEncoded) "+" else "%2B")
        } else if (codePoint < 0x20
            || codePoint == 0x7f
            || codePoint >= 0x80 && asciiOnly
            || encodeSet.indexOf(codePoint.toChar()) != -1
            || codePoint == '%'.toInt() && (!alreadyEncoded || strict && !percentEncoded(input, i,
                limit))) {
          // Percent encode this character.
          if (encodedCharBuffer == null) {
            encodedCharBuffer = Buffer()
          }

          if (charset == null || charset == UTF_8) {
            encodedCharBuffer.writeUtf8CodePoint(codePoint)
          } else {
            encodedCharBuffer.writeString(input, i, i + Character.charCount(codePoint), charset)
          }

          while (!encodedCharBuffer.exhausted()) {
            val b = encodedCharBuffer.readByte().toInt() and 0xff
            out.writeByte('%'.toInt())
            out.writeByte(HEX_DIGITS[b shr 4 and 0xf].toInt())
            out.writeByte(HEX_DIGITS[b and 0xf].toInt())
          }
        } else {
          // This character doesn't need encoding. Just copy it over.
          out.writeUtf8CodePoint(codePoint)
        }
        i += Character.charCount(codePoint)
      }
    }

    @JvmStatic
    internal fun canonicalize(
      input: String, encodeSet: String, alreadyEncoded: Boolean, strict: Boolean,
      plusIsSpace: Boolean, asciiOnly: Boolean, charset: Charset?
    ): String = canonicalize(input, 0, input.length, encodeSet, alreadyEncoded, strict, plusIsSpace,
        asciiOnly, charset)

    @JvmStatic
    internal fun canonicalize(
      input: String, encodeSet: String, alreadyEncoded: Boolean, strict: Boolean,
      plusIsSpace: Boolean, asciiOnly: Boolean
    ): String = canonicalize(
        input, 0, input.length, encodeSet, alreadyEncoded, strict, plusIsSpace, asciiOnly, null)
  }
}
