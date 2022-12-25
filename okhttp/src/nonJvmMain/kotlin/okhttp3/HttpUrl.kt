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

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.CommonHttpUrl
import okhttp3.internal.CommonHttpUrl.commonAddEncodedPathSegment
import okhttp3.internal.CommonHttpUrl.commonAddEncodedPathSegments
import okhttp3.internal.CommonHttpUrl.commonAddEncodedQueryParameter
import okhttp3.internal.CommonHttpUrl.commonAddPathSegment
import okhttp3.internal.CommonHttpUrl.commonAddPathSegments
import okhttp3.internal.CommonHttpUrl.commonAddQueryParameter
import okhttp3.internal.CommonHttpUrl.commonBuild
import okhttp3.internal.CommonHttpUrl.commonEncodedFragment
import okhttp3.internal.CommonHttpUrl.commonEncodedPassword
import okhttp3.internal.CommonHttpUrl.commonEncodedPath
import okhttp3.internal.CommonHttpUrl.commonEncodedPathSegments
import okhttp3.internal.CommonHttpUrl.commonEncodedQuery
import okhttp3.internal.CommonHttpUrl.commonEncodedUsername
import okhttp3.internal.CommonHttpUrl.commonEquals
import okhttp3.internal.CommonHttpUrl.commonFragment
import okhttp3.internal.CommonHttpUrl.commonHashCode
import okhttp3.internal.CommonHttpUrl.commonHost
import okhttp3.internal.CommonHttpUrl.commonIsHttps
import okhttp3.internal.CommonHttpUrl.commonNewBuilder
import okhttp3.internal.CommonHttpUrl.commonParse
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
import okhttp3.internal.CommonHttpUrl.commonRemoveAllEncodedQueryParameters
import okhttp3.internal.CommonHttpUrl.commonRemoveAllQueryParameters
import okhttp3.internal.CommonHttpUrl.commonRemovePathSegment
import okhttp3.internal.CommonHttpUrl.commonResolve
import okhttp3.internal.CommonHttpUrl.commonScheme
import okhttp3.internal.CommonHttpUrl.commonSetEncodedPathSegment
import okhttp3.internal.CommonHttpUrl.commonSetEncodedQueryParameter
import okhttp3.internal.CommonHttpUrl.commonSetPathSegment
import okhttp3.internal.CommonHttpUrl.commonSetQueryParameter
import okhttp3.internal.CommonHttpUrl.commonToHttpUrl
import okhttp3.internal.CommonHttpUrl.commonToHttpUrlOrNull
import okhttp3.internal.CommonHttpUrl.commonToString
import okhttp3.internal.CommonHttpUrl.commonUsername

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
  actual val scheme: String,
  actual val username: String,
  actual val password: String,
  actual val host: String,
  actual val port: Int,
  actual val pathSegments: List<String>,
  internal actual val queryNamesAndValues: List<String?>?,
  actual val fragment: String?,
  internal actual val url: String
) {

  actual val isHttps: Boolean
    get() = commonIsHttps

  actual val encodedUsername: String
    get() = commonEncodedUsername

  actual val encodedPassword: String
    get() = commonEncodedPassword

  actual val pathSize: Int
    get() = commonPathSize

  actual val encodedPath: String
    get() = commonEncodedPath

  actual val encodedPathSegments: List<String>
    get() = commonEncodedPathSegments

  actual val encodedQuery: String?
    get() = commonEncodedQuery

  actual val query: String?
    get() = commonQuery

  actual val querySize: Int
    get() = commonQuerySize

  actual fun queryParameter(name: String): String? = commonQueryParameter(name)

  actual val queryParameterNames: Set<String>
    get() = commonQueryParameterNames

  actual fun queryParameterValues(name: String): List<String?> = commonQueryParameterValues(name)

  actual fun queryParameterName(index: Int): String = commonQueryParameterName(index)

  actual fun queryParameterValue(index: Int): String? = commonQueryParameterValue(index)

  actual val encodedFragment: String?
    get() = commonEncodedFragment

  actual fun redact(): String = commonRedact()

  actual fun resolve(link: String): HttpUrl? = commonResolve(link)

  actual fun newBuilder(): HttpUrl.Builder = commonNewBuilder()

  actual fun newBuilder(link: String): Builder? = commonNewBuilder(link)

  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  override fun toString(): String = commonToString()

  actual companion object {
    actual fun String.toHttpUrl(): HttpUrl = commonToHttpUrl()

    actual fun String.toHttpUrlOrNull(): HttpUrl? = commonToHttpUrlOrNull()

    actual fun defaultPort(scheme: String): Int = CommonHttpUrl.commonDefaultPort(scheme)
  }

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

    actual fun addPathSegment(pathSegment: String) = commonAddPathSegment(pathSegment)

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
     * starts with a slash, the resulting URL will have empty path segment.
     */
    actual fun addPathSegments(pathSegments: String): Builder = commonAddPathSegments(pathSegments)

    actual fun addEncodedPathSegment(encodedPathSegment: String) = commonAddEncodedPathSegment(encodedPathSegment)

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
     * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
     */
    actual fun addEncodedPathSegments(encodedPathSegments: String): Builder =
      commonAddEncodedPathSegments(encodedPathSegments)


    actual fun setPathSegment(index: Int, pathSegment: String) = commonSetPathSegment(index, pathSegment)

    actual fun setEncodedPathSegment(index: Int, encodedPathSegment: String) =
      commonSetEncodedPathSegment(index, encodedPathSegment)

    actual fun removePathSegment(index: Int) = commonRemovePathSegment(index)

    actual fun encodedPath(encodedPath: String) = commonEncodedPath(encodedPath)

    actual fun query(query: String?) = commonQuery(query)

    actual fun encodedQuery(encodedQuery: String?) = commonEncodedQuery(encodedQuery)

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    actual fun addQueryParameter(name: String, value: String?) = commonAddQueryParameter(name, value)

    /** Adds the pre-encoded query parameter to this URL's query string. */
    actual fun addEncodedQueryParameter(encodedName: String, encodedValue: String?) =
      commonAddEncodedQueryParameter(encodedName, encodedValue)

    actual fun setQueryParameter(name: String, value: String?) = commonSetQueryParameter(name, value)

    actual fun setEncodedQueryParameter(encodedName: String, encodedValue: String?) =
      commonSetEncodedQueryParameter(encodedName, encodedValue)

    actual fun removeAllQueryParameters(name: String) = commonRemoveAllQueryParameters(name)

    actual fun removeAllEncodedQueryParameters(encodedName: String) = commonRemoveAllEncodedQueryParameters(encodedName)

    actual fun fragment(fragment: String?) = commonFragment(fragment)

    actual fun encodedFragment(encodedFragment: String?) = commonEncodedFragment(encodedFragment)

    actual fun build(): HttpUrl = commonBuild()

    override fun toString(): String = commonToString()

    internal actual fun parse(base: HttpUrl?, input: String): Builder = commonParse(base, input)
  }
}
