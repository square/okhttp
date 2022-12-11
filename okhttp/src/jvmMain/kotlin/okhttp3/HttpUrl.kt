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

import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.CommonHttpUrl.FRAGMENT_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.FRAGMENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.PASSWORD_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.PATH_SEGMENT_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.PATH_SEGMENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.QUERY_COMPONENT_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.QUERY_COMPONENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.QUERY_COMPONENT_REENCODE_SET
import okhttp3.internal.CommonHttpUrl.QUERY_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.USERNAME_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.commonDefaultPort
import okhttp3.internal.CommonHttpUrl.commonEncodedFragment
import okhttp3.internal.CommonHttpUrl.commonEncodedPassword
import okhttp3.internal.CommonHttpUrl.commonEncodedPath
import okhttp3.internal.CommonHttpUrl.commonEncodedPathSegments
import okhttp3.internal.CommonHttpUrl.commonEncodedQuery
import okhttp3.internal.CommonHttpUrl.commonEncodedUsername
import okhttp3.internal.CommonHttpUrl.commonEquals
import okhttp3.internal.CommonHttpUrl.commonHashCode
import okhttp3.internal.CommonHttpUrl.commonHost
import okhttp3.internal.CommonHttpUrl.commonNewBuilder
import okhttp3.internal.CommonHttpUrl.commonPassword
import okhttp3.internal.CommonHttpUrl.commonPathSize
import okhttp3.internal.CommonHttpUrl.commonPort
import okhttp3.internal.CommonHttpUrl.commonQuery
import okhttp3.internal.CommonHttpUrl.commonQueryParameter
import okhttp3.internal.CommonHttpUrl.commonQueryParameterName
import okhttp3.internal.CommonHttpUrl.commonQueryParameterNames
import okhttp3.internal.CommonHttpUrl.commonQueryParameterValue
import okhttp3.internal.CommonHttpUrl.commonQueryParameterValues
import okhttp3.internal.CommonHttpUrl.commonQuerySize
import okhttp3.internal.CommonHttpUrl.commonRedact
import okhttp3.internal.CommonHttpUrl.commonResolve
import okhttp3.internal.CommonHttpUrl.commonScheme
import okhttp3.internal.CommonHttpUrl.commonToString
import okhttp3.internal.CommonHttpUrl.commonUsername
import okhttp3.internal.CommonHttpUrl.percentDecode
import okhttp3.internal.CommonHttpUrl.toQueryString
import okhttp3.internal.HttpUrlCommon.canonicalize
import okhttp3.internal.JvmHttpUrl.INVALID_HOST
import okhttp3.internal.JvmHttpUrl.parsePort
import okhttp3.internal.JvmHttpUrl.portColonOffset
import okhttp3.internal.JvmHttpUrl.schemeDelimiterOffset
import okhttp3.internal.JvmHttpUrl.slashCount
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.delimiterOffset
import okhttp3.internal.indexOfFirstNonAsciiWhitespace
import okhttp3.internal.indexOfLastNonAsciiWhitespace
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okhttp3.internal.toCanonicalHost

/**
 * A uniform resource locator (URL) with a scheme of either `http` or `https`. Use this class to
 * compose and decompose Internet addresses. For example, this code will compose and print a URL for
 * Google search:
 *
 * ```java
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
 * ```java
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
 * ```java
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
 * ```java
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
 * `http://ho™ail.com`. To mitigate this, the single character (™) maps to the string (tm). There
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
 *  * https://example.net/
 *
 *  * https://example.com/
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
 * ```java
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
 * This class has a modern API. It avoids punitive checked exceptions: [toHttpUrl] throws
 * [IllegalArgumentException] on invalid input or [toHttpUrlOrNull] returns null if the input is an
 * invalid URL. You can even be explicit about whether each component has been encoded already.
 *
 * [idna]: http://www.unicode.org/reports/tr46/#ToASCII
 */
actual class HttpUrl internal actual constructor(
  @get:JvmName("scheme") actual val scheme: String,

  @get:JvmName("username") actual val username: String,

  @get:JvmName("password") actual val password: String,

  @get:JvmName("host") actual val host: String,

  @get:JvmName("port") actual val port: Int,

  @get:JvmName("pathSegments") actual val pathSegments: List<String>,

  /**
   * Alternating, decoded query names and values, or null for no query. Names may be empty or
   * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
   * empty, or non-empty.
   */
  internal actual val queryNamesAndValues: List<String?>?,

  @get:JvmName("fragment") actual val fragment: String?,

  /** Canonical URL. */
  internal actual val url: String
) {
  actual val isHttps: Boolean
    get() = scheme == "https"

  /** Returns this URL as a [java.net.URL][URL]. */
  @JvmName("url") fun toUrl(): URL {
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
  @JvmName("uri") fun toUri(): URI {
    val uri = newBuilder().reencodeForUri().toString()
    return try {
      URI(uri)
    } catch (e: URISyntaxException) {
      // Unlikely edge case: the URI has a forbidden character in the fragment. Strip it & retry.
      try {
        val stripped = uri.replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]"), "")
        URI.create(stripped)
      } catch (e1: Exception) {
        throw RuntimeException(e) // Unexpected!
      }
    }
  }

  @get:JvmName("encodedUsername") actual val encodedUsername: String
    get() = commonEncodedUsername

  @get:JvmName("encodedPassword") actual val encodedPassword: String
    get() = commonEncodedPassword

  @get:JvmName("pathSize")
  actual val pathSize: Int
    get() = commonPathSize

  @get:JvmName("encodedPath") actual val encodedPath: String
    get() = commonEncodedPath

  @get:JvmName("encodedPathSegments") actual val encodedPathSegments: List<String>
    get() = commonEncodedPathSegments

  @get:JvmName("encodedQuery") actual val encodedQuery: String?
    get() = commonEncodedQuery

  @get:JvmName("query") actual val query: String?
    get() = commonQuery

  @get:JvmName("querySize") actual val querySize: Int
    get() = commonQuerySize

  actual fun queryParameter(name: String): String? = commonQueryParameter(name)

  actual @get:JvmName("queryParameterNames") val queryParameterNames: Set<String>
    get() = commonQueryParameterNames

  actual fun queryParameterValues(name: String): List<String?> = commonQueryParameterValues(name)

  actual fun queryParameterName(index: Int): String = commonQueryParameterName(index)

  actual fun queryParameterValue(index: Int): String? = commonQueryParameterValue(index)

  @get:JvmName("encodedFragment")
  actual val encodedFragment: String?
    get() = commonEncodedFragment

  actual fun redact(): String = commonRedact()

  actual fun resolve(link: String): HttpUrl? = commonResolve(link)

  actual fun newBuilder(): Builder = commonNewBuilder()

  actual fun newBuilder(link: String): Builder? = commonNewBuilder(link)

  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  override fun toString(): String = commonToString()

  /**
   * Returns the domain name of this URL's [host] that is one level beneath the public suffix by
   * consulting the [public suffix list](https://publicsuffix.org). Returns null if this URL's
   * [host] is an IP address or is considered a public suffix by the public suffix list.
   *
   * In general this method **should not** be used to test whether a domain is valid or routable.
   * Instead, DNS is the recommended source for that information.
   *
   * | URL                           | `topPrivateDomain()` |
   * | :---------------------------- | :------------------- |
   * | `http://google.com`           | `"google.com"`       |
   * | `http://adwords.google.co.uk` | `"google.co.uk"`     |
   * | `http://square`               | null                 |
   * | `http://co.uk`                | null                 |
   * | `http://localhost`            | null                 |
   * | `http://127.0.0.1`            | null                 |
   */
  fun topPrivateDomain(): String? {
    return if (host.canParseAsIpAddress()) {
      null
    } else {
      PublicSuffixDatabase.get().getEffectiveTldPlusOne(host)
    }
  }

  @JvmName("-deprecated_url")
  @Deprecated(
      message = "moved to toUrl()",
      replaceWith = ReplaceWith(expression = "toUrl()"),
      level = DeprecationLevel.ERROR)
  fun url(): URL = toUrl()

  @JvmName("-deprecated_uri")
  @Deprecated(
      message = "moved to toUri()",
      replaceWith = ReplaceWith(expression = "toUri()"),
      level = DeprecationLevel.ERROR)
  fun uri(): URI = toUri()

  @JvmName("-deprecated_scheme")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "scheme"),
      level = DeprecationLevel.ERROR)
  fun scheme(): String = scheme

  @JvmName("-deprecated_encodedUsername")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedUsername"),
      level = DeprecationLevel.ERROR)
  fun encodedUsername(): String = encodedUsername

  @JvmName("-deprecated_username")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "username"),
      level = DeprecationLevel.ERROR)
  fun username(): String = username

  @JvmName("-deprecated_encodedPassword")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedPassword"),
      level = DeprecationLevel.ERROR)
  fun encodedPassword(): String = encodedPassword

  @JvmName("-deprecated_password")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "password"),
      level = DeprecationLevel.ERROR)
  fun password(): String = password

  @JvmName("-deprecated_host")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "host"),
      level = DeprecationLevel.ERROR)
  fun host(): String = host

  @JvmName("-deprecated_port")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "port"),
      level = DeprecationLevel.ERROR)
  fun port(): Int = port

  @JvmName("-deprecated_pathSize")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "pathSize"),
      level = DeprecationLevel.ERROR)
  fun pathSize(): Int = pathSize

  @JvmName("-deprecated_encodedPath")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedPath"),
      level = DeprecationLevel.ERROR)
  fun encodedPath(): String = encodedPath

  @JvmName("-deprecated_encodedPathSegments")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedPathSegments"),
      level = DeprecationLevel.ERROR)
  fun encodedPathSegments(): List<String> = encodedPathSegments

  @JvmName("-deprecated_pathSegments")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "pathSegments"),
      level = DeprecationLevel.ERROR)
  fun pathSegments(): List<String> = pathSegments

  @JvmName("-deprecated_encodedQuery")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedQuery"),
      level = DeprecationLevel.ERROR)
  fun encodedQuery(): String? = encodedQuery

  @JvmName("-deprecated_query")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "query"),
      level = DeprecationLevel.ERROR)
  fun query(): String? = query

  @JvmName("-deprecated_querySize")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "querySize"),
      level = DeprecationLevel.ERROR)
  fun querySize(): Int = querySize

  @JvmName("-deprecated_queryParameterNames")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "queryParameterNames"),
      level = DeprecationLevel.ERROR)
  fun queryParameterNames(): Set<String> = queryParameterNames

  @JvmName("-deprecated_encodedFragment")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "encodedFragment"),
      level = DeprecationLevel.ERROR)
  fun encodedFragment(): String? = encodedFragment

  @JvmName("-deprecated_fragment")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "fragment"),
      level = DeprecationLevel.ERROR)
  fun fragment(): String? = fragment

  actual class Builder {
    internal actual var scheme: String? = null
    internal actual var encodedUsername = ""
    internal actual var encodedPassword = ""
    internal actual var host: String? = null
    internal actual var port = -1
    internal actual val encodedPathSegments = mutableListOf<String>("")
    internal actual var encodedQueryNamesAndValues: MutableList<String?>? = null
    internal actual var encodedFragment: String? = null

    /**
     * @param scheme either "http" or "https".
     */
    actual fun scheme(scheme: String) = commonScheme(scheme)

    actual fun username(username: String) = commonUsername(username)

    actual fun encodedUsername(encodedUsername: String) = commonEncodedUsername(encodedUsername)

    actual fun password(password: String) = commonPassword(password)

    actual fun encodedPassword(encodedPassword: String) = commonEncodedPassword(encodedPassword)

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     * address.
     */
    actual fun host(host: String) = commonHost(host)

    actual fun port(port: Int) = commonPort(port)

    private fun effectivePort(): Int {
      return if (port != -1) port else defaultPort(scheme!!)
    }

    actual fun addPathSegment(pathSegment: String) = apply {
      push(pathSegment, 0, pathSegment.length, addTrailingSlash = false, alreadyEncoded = false)
    }

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
     * starts with a slash, the resulting URL will have empty path segment.
     */
    actual fun addPathSegments(pathSegments: String): Builder = addPathSegments(pathSegments, false)

    actual fun addEncodedPathSegment(encodedPathSegment: String) = apply {
      push(encodedPathSegment, 0, encodedPathSegment.length, addTrailingSlash = false,
          alreadyEncoded = true)
    }

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
     * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
     */
    actual fun addEncodedPathSegments(encodedPathSegments: String): Builder =
        addPathSegments(encodedPathSegments, true)

    private fun addPathSegments(pathSegments: String, alreadyEncoded: Boolean) = apply {
      var offset = 0
      do {
        val segmentEnd = pathSegments.delimiterOffset("/\\", offset, pathSegments.length)
        val addTrailingSlash = segmentEnd < pathSegments.length
        push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded)
        offset = segmentEnd + 1
      } while (offset <= pathSegments.length)
    }

    actual fun setPathSegment(index: Int, pathSegment: String) = apply {
      val canonicalPathSegment = pathSegment.canonicalize(encodeSet = PATH_SEGMENT_ENCODE_SET)
      require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
        "unexpected path segment: $pathSegment"
      }
      encodedPathSegments[index] = canonicalPathSegment
    }

    actual fun setEncodedPathSegment(index: Int, encodedPathSegment: String) = apply {
      val canonicalPathSegment = encodedPathSegment.canonicalize(
          encodeSet = PATH_SEGMENT_ENCODE_SET,
          alreadyEncoded = true
      )
      encodedPathSegments[index] = canonicalPathSegment
      require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
        "unexpected path segment: $encodedPathSegment"
      }
    }

    actual fun removePathSegment(index: Int) = apply {
      encodedPathSegments.removeAt(index)
      if (encodedPathSegments.isEmpty()) {
        encodedPathSegments.add("") // Always leave at least one '/'.
      }
    }

    actual fun encodedPath(encodedPath: String) = apply {
      require(encodedPath.startsWith("/")) { "unexpected encodedPath: $encodedPath" }
      resolvePath(encodedPath, 0, encodedPath.length)
    }

    actual fun query(query: String?) = apply {
      this.encodedQueryNamesAndValues = query?.canonicalize(
          encodeSet = QUERY_ENCODE_SET,
          plusIsSpace = true
      )?.toQueryNamesAndValues()
    }

    actual fun encodedQuery(encodedQuery: String?) = apply {
      this.encodedQueryNamesAndValues = encodedQuery?.canonicalize(
          encodeSet = QUERY_ENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true
      )?.toQueryNamesAndValues()
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    actual fun addQueryParameter(name: String, value: String?) = apply {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
      encodedQueryNamesAndValues!!.add(name.canonicalize(
          encodeSet = QUERY_COMPONENT_ENCODE_SET,
          plusIsSpace = true
      ))
      encodedQueryNamesAndValues!!.add(value?.canonicalize(
          encodeSet = QUERY_COMPONENT_ENCODE_SET,
          plusIsSpace = true
      ))
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    actual fun addEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
      encodedQueryNamesAndValues!!.add(encodedName.canonicalize(
          encodeSet = QUERY_COMPONENT_REENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true
      ))
      encodedQueryNamesAndValues!!.add(encodedValue?.canonicalize(
          encodeSet = QUERY_COMPONENT_REENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true
      ))
    }

    actual fun setQueryParameter(name: String, value: String?) = apply {
      removeAllQueryParameters(name)
      addQueryParameter(name, value)
    }

    actual fun setEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
      removeAllEncodedQueryParameters(encodedName)
      addEncodedQueryParameter(encodedName, encodedValue)
    }

    actual fun removeAllQueryParameters(name: String) = apply {
      if (encodedQueryNamesAndValues == null) return this
      val nameToRemove = name.canonicalize(
          encodeSet = QUERY_COMPONENT_ENCODE_SET,
          plusIsSpace = true
      )
      removeAllCanonicalQueryParameters(nameToRemove)
    }

    actual fun removeAllEncodedQueryParameters(encodedName: String) = apply {
      if (encodedQueryNamesAndValues == null) return this
      removeAllCanonicalQueryParameters(encodedName.canonicalize(
          encodeSet = QUERY_COMPONENT_REENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true
      ))
    }

    private fun removeAllCanonicalQueryParameters(canonicalName: String) {
      for (i in encodedQueryNamesAndValues!!.size - 2 downTo 0 step 2) {
        if (canonicalName == encodedQueryNamesAndValues!![i]) {
          encodedQueryNamesAndValues!!.removeAt(i + 1)
          encodedQueryNamesAndValues!!.removeAt(i)
          if (encodedQueryNamesAndValues!!.isEmpty()) {
            encodedQueryNamesAndValues = null
            return
          }
        }
      }
    }

    actual fun fragment(fragment: String?) = apply {
      this.encodedFragment = fragment?.canonicalize(
          encodeSet = FRAGMENT_ENCODE_SET,
          unicodeAllowed = true
      )
    }

    actual fun encodedFragment(encodedFragment: String?) = apply {
      this.encodedFragment = encodedFragment?.canonicalize(
          encodeSet = FRAGMENT_ENCODE_SET,
          alreadyEncoded = true,
          unicodeAllowed = true
      )
    }

    /**
     * Re-encodes the components of this URL so that it satisfies (obsolete) RFC 2396, which is
     * particularly strict for certain components.
     */
    internal fun reencodeForUri() = apply {
      host = host?.replace(Regex("[\"<>^`{|}]"), "")

      for (i in 0 until encodedPathSegments.size) {
        encodedPathSegments[i] = encodedPathSegments[i].canonicalize(
            encodeSet = PATH_SEGMENT_ENCODE_SET_URI,
            alreadyEncoded = true,
            strict = true
        )
      }

      val encodedQueryNamesAndValues = this.encodedQueryNamesAndValues
      if (encodedQueryNamesAndValues != null) {
        for (i in 0 until encodedQueryNamesAndValues.size) {
          encodedQueryNamesAndValues[i] = encodedQueryNamesAndValues[i]?.canonicalize(
              encodeSet = QUERY_COMPONENT_ENCODE_SET_URI,
              alreadyEncoded = true,
              strict = true,
              plusIsSpace = true
          )
        }
      }

      encodedFragment = encodedFragment?.canonicalize(
          encodeSet = FRAGMENT_ENCODE_SET_URI,
          alreadyEncoded = true,
          strict = true,
          unicodeAllowed = true
      )
    }

    actual fun build(): HttpUrl {
      @Suppress("UNCHECKED_CAST") // percentDecode returns either List<String?> or List<String>.
      return HttpUrl(
          scheme = scheme ?: throw IllegalStateException("scheme == null"),
          username = encodedUsername.percentDecode(),
          password = encodedPassword.percentDecode(),
          host = host ?: throw IllegalStateException("host == null"),
          port = effectivePort(),
          pathSegments = encodedPathSegments.map { it.percentDecode() },
          queryNamesAndValues = encodedQueryNamesAndValues?.map { it?.percentDecode(plusIsSpace = true) },
          fragment = encodedFragment?.percentDecode(),
          url = toString()
      )
    }

    override fun toString(): String {
      return buildString {
        if (scheme != null) {
          append(scheme)
          append("://")
        } else {
          append("//")
        }

        if (encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty()) {
          append(encodedUsername)
          if (encodedPassword.isNotEmpty()) {
            append(':')
            append(encodedPassword)
          }
          append('@')
        }

        if (host != null) {
          if (':' in host!!) {
            // Host is an IPv6 address.
            append('[')
            append(host)
            append(']')
          } else {
            append(host)
          }
        }

        if (port != -1 || scheme != null) {
          val effectivePort = effectivePort()
          if (scheme == null || effectivePort != defaultPort(scheme!!)) {
            append(':')
            append(effectivePort)
          }
        }

        encodedPathSegments.toPathString(this)

        if (encodedQueryNamesAndValues != null) {
          append('?')
          encodedQueryNamesAndValues!!.toQueryString(this)
        }

        if (encodedFragment != null) {
          append('#')
          append(encodedFragment)
        }
      }
    }

    actual internal fun parse(base: HttpUrl?, input: String): Builder {
      var pos = input.indexOfFirstNonAsciiWhitespace()
      val limit = input.indexOfLastNonAsciiWhitespace(pos)

      // Scheme.
      val schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit)
      if (schemeDelimiterOffset != -1) {
        when {
          input.startsWith("https:", ignoreCase = true, startIndex = pos) -> {
            this.scheme = "https"
            pos += "https:".length
          }
          input.startsWith("http:", ignoreCase = true, startIndex = pos) -> {
            this.scheme = "http"
            pos += "http:".length
          }
          else -> throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '" +
              input.substring(0, schemeDelimiterOffset) + "'")
        }
      } else if (base != null) {
        this.scheme = base.scheme
      } else {
        val truncated = if (input.length > 6) input.take(6) + "..." else input
        throw IllegalArgumentException(
            "Expected URL scheme 'http' or 'https' but no scheme was found for $truncated")
      }

      // Authority.
      var hasUsername = false
      var hasPassword = false
      val slashCount = input.slashCount(pos, limit)
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
          val componentDelimiterOffset = input.delimiterOffset("@/\\?#", pos, limit)
          val c = if (componentDelimiterOffset != limit) {
            input[componentDelimiterOffset].code
          } else {
            -1
          }
          when (c) {
            '@'.code -> {
              // User info precedes.
              if (!hasPassword) {
                val passwordColonOffset = input.delimiterOffset(':', pos, componentDelimiterOffset)
                val canonicalUsername = input.canonicalize(
                    pos = pos,
                    limit = passwordColonOffset,
                    encodeSet = USERNAME_ENCODE_SET,
                    alreadyEncoded = true
                )
                this.encodedUsername = if (hasUsername) {
                  this.encodedUsername + "%40" + canonicalUsername
                } else {
                  canonicalUsername
                }
                if (passwordColonOffset != componentDelimiterOffset) {
                  hasPassword = true
                  this.encodedPassword = input.canonicalize(
                      pos = passwordColonOffset + 1,
                      limit = componentDelimiterOffset,
                      encodeSet = PASSWORD_ENCODE_SET,
                      alreadyEncoded = true
                  )
                }
                hasUsername = true
              } else {
                this.encodedPassword = this.encodedPassword + "%40" + input.canonicalize(
                    pos = pos,
                    limit = componentDelimiterOffset,
                    encodeSet = PASSWORD_ENCODE_SET,
                    alreadyEncoded = true
                )
              }
              pos = componentDelimiterOffset + 1
            }

            -1, '/'.code, '\\'.code, '?'.code, '#'.code -> {
              // Host info precedes.
              val portColonOffset = portColonOffset(input, pos, componentDelimiterOffset)
              if (portColonOffset + 1 < componentDelimiterOffset) {
                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset)
                require(port != -1) {
                  "Invalid URL port: \"${input.substring(portColonOffset + 1,
                      componentDelimiterOffset)}\""
                }
              } else {
                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
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
        this.encodedUsername = base.encodedUsername
        this.encodedPassword = base.encodedPassword
        this.host = base.host
        this.port = base.port
        this.encodedPathSegments.clear()
        this.encodedPathSegments.addAll(base.encodedPathSegments)
        if (pos == limit || input[pos] == '#') {
          encodedQuery(base.encodedQuery)
        }
      }

      // Resolve the relative path.
      val pathDelimiterOffset = input.delimiterOffset("?#", pos, limit)
      resolvePath(input, pos, pathDelimiterOffset)
      pos = pathDelimiterOffset

      // Query.
      if (pos < limit && input[pos] == '?') {
        val queryDelimiterOffset = input.delimiterOffset('#', pos, limit)
        this.encodedQueryNamesAndValues = input.canonicalize(
            pos = pos + 1,
            limit = queryDelimiterOffset,
            encodeSet = QUERY_ENCODE_SET,
            alreadyEncoded = true,
            plusIsSpace = true
        ).toQueryNamesAndValues()
        pos = queryDelimiterOffset
      }

      // Fragment.
      if (pos < limit && input[pos] == '#') {
        this.encodedFragment = input.canonicalize(
            pos = pos + 1,
            limit = limit,
            encodeSet = FRAGMENT_ENCODE_SET,
            alreadyEncoded = true,
            unicodeAllowed = true
        )
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
        val pathSegmentDelimiterOffset = input.delimiterOffset("/\\", i, limit)
        val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
        push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
        i = pathSegmentDelimiterOffset
        if (segmentHasTrailingSlash) i++
      }
    }

    /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
    private fun push(
      input: String,
      pos: Int,
      limit: Int,
      addTrailingSlash: Boolean,
      alreadyEncoded: Boolean
    ) {
      val segment = input.canonicalize(
          pos = pos,
          limit = limit,
          encodeSet = PATH_SEGMENT_ENCODE_SET,
          alreadyEncoded = alreadyEncoded
      )
      if (isDot(segment)) {
        return // Skip '.' path segments.
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
      return input == ".." ||
          input.equals("%2e.", ignoreCase = true) ||
          input.equals(".%2e", ignoreCase = true) ||
          input.equals("%2e%2e", ignoreCase = true)
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
      if (removed.isEmpty() && encodedPathSegments.isNotEmpty()) {
        encodedPathSegments[encodedPathSegments.size - 1] = ""
      } else {
        encodedPathSegments.add("")
      }
    }
  }

  actual companion object {
    @JvmStatic
    actual fun defaultPort(scheme: String): Int = commonDefaultPort(scheme)

    /** Returns a path string for this list of path segments. */
    internal fun List<String>.toPathString(out: StringBuilder) {
      for (i in 0 until size) {
        out.append('/')
        out.append(this[i])
      }
    }

    /**
     * Cuts this string up into alternating parameter names and values. This divides a query string
     * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
     * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
     */
    internal fun String.toQueryNamesAndValues(): MutableList<String?> {
      val result = mutableListOf<String?>()
      var pos = 0
      while (pos <= length) {
        var ampersandOffset = indexOf('&', pos)
        if (ampersandOffset == -1) ampersandOffset = length

        val equalsOffset = indexOf('=', pos)
        if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
          result.add(substring(pos, ampersandOffset))
          result.add(null) // No value for this name.
        } else {
          result.add(substring(pos, equalsOffset))
          result.add(substring(equalsOffset + 1, ampersandOffset))
        }
        pos = ampersandOffset + 1
      }
      return result
    }

    /**
     * Returns a new [HttpUrl] representing this.
     *
     * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
     */
    @JvmStatic
    @JvmName("get") actual fun String.toHttpUrl(): HttpUrl = Builder().parse(null, this).build()

    /**
     * Returns a new `HttpUrl` representing `url` if it is a well-formed HTTP or HTTPS URL, or null
     * if it isn't.
     */
    @JvmStatic
    @JvmName("parse")
    actual fun String.toHttpUrlOrNull(): HttpUrl? {
      return try {
        toHttpUrl()
      } catch (_: IllegalArgumentException) {
        null
      }
    }

    /**
     * Returns an [HttpUrl] for this if its protocol is `http` or `https`, or null if it has any
     * other protocol.
     */
    @JvmStatic
    @JvmName("get") fun URL.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmStatic
    @JvmName("get") fun URI.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "url.toHttpUrl()",
            imports = ["okhttp3.HttpUrl.Companion.toHttpUrl"]),
        level = DeprecationLevel.ERROR)
    fun get(url: String): HttpUrl = url.toHttpUrl()

    @JvmName("-deprecated_parse")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "url.toHttpUrlOrNull()",
            imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]),
        level = DeprecationLevel.ERROR)
    fun parse(url: String): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "url.toHttpUrlOrNull()",
            imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]),
        level = DeprecationLevel.ERROR)
    fun get(url: URL): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(
            expression = "uri.toHttpUrlOrNull()",
            imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]),
        level = DeprecationLevel.ERROR)
    fun get(uri: URI): HttpUrl? = uri.toHttpUrlOrNull()
  }
}
