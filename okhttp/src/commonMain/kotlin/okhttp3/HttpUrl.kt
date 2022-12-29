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

import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic

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
expect class HttpUrl internal constructor(
  scheme: String,
  username: String,
  password: String,
  host: String,
  port: Int,
  pathSegments: List<String>,
  queryNamesAndValues: List<String?>?,
  fragment: String?,
  url: String
) {

  /** Either "http" or "https". */
  val scheme: String

  /**
   * The decoded username, or an empty string if none is present.
   *
   * | URL                              | `username()` |
   * | :------------------------------- | :----------- |
   * | `http://host/`                   | `""`         |
   * | `http://username@host/`          | `"username"` |
   * | `http://username:password@host/` | `"username"` |
   * | `http://a%20b:c%20d@host/`       | `"a b"`      |
   */
  val username: String

  /**
   * Returns the decoded password, or an empty string if none is present.
   *
   * | URL                              | `password()` |
   * | :------------------------------- | :----------- |
   * | `http://host/`                   | `""`         |
   * | `http://username@host/`          | `""`         |
   * | `http://username:password@host/` | `"password"` |
   * | `http://a%20b:c%20d@host/`       | `"c d"`      |
   */
  val password: String

  /**
   * The host address suitable for use with [InetAddress.getAllByName]. May be:
   *
   *  * A regular host name, like `android.com`.
   *
   *  * An IPv4 address, like `127.0.0.1`.
   *
   *  * An IPv6 address, like `::1`. Note that there are no square braces.
   *
   *  * An encoded IDN, like `xn--n3h.net`.
   *
   * | URL                   | `host()`        |
   * | :-------------------- | :-------------- |
   * | `http://android.com/` | `"android.com"` |
   * | `http://127.0.0.1/`   | `"127.0.0.1"`   |
   * | `http://[::1]/`       | `"::1"`         |
   * | `http://xn--n3h.net/` | `"xn--n3h.net"` |
   */
  val host: String

  /**
   * The explicitly-specified port if one was provided, or the default port for this URL's scheme.
   * For example, this returns 8443 for `https://square.com:8443/` and 443 for
   * `https://square.com/`. The result is in `[1..65535]`.
   *
   * | URL                 | `port()` |
   * | :------------------ | :------- |
   * | `http://host/`      | `80`     |
   * | `http://host:8000/` | `8000`   |
   * | `https://host/`     | `443`    |
   */
  val port: Int

  /**
   * A list of path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This list is
   * never empty though it may contain a single empty string.
   *
   * | URL                      | `pathSegments()`    |
   * | :----------------------- | :------------------ |
   * | `http://host/`           | `[""]`              |
   * | `http://host/a/b/c"`     | `["a", "b", "c"]`   |
   * | `http://host/a/b%20c/d"` | `["a", "b c", "d"]` |
   */
  val pathSegments: List<String>

  /**
   * This URL's fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has no
   * fragment.
   *
   * | URL                    | `fragment()` |
   * | :--------------------- | :----------- |
   * | `http://host/`         | null         |
   * | `http://host/#`        | `""`         |
   * | `http://host/#abc`     | `"abc"`      |
   * | `http://host/#abc|def` | `"abc|def"`  |
   */
  val fragment: String?

  val isHttps: Boolean

  /**
   * The username, or an empty string if none is set.
   *
   * | URL                              | `encodedUsername()` |
   * | :------------------------------- | :------------------ |
   * | `http://host/`                   | `""`                |
   * | `http://username@host/`          | `"username"`        |
   * | `http://username:password@host/` | `"username"`        |
   * | `http://a%20b:c%20d@host/`       | `"a%20b"`           |
   */
  val encodedUsername: String

  /**
   * The password, or an empty string if none is set.
   *
   * | URL                              | `encodedPassword()` |
   * | :--------------------------------| :------------------ |
   * | `http://host/`                   | `""`                |
   * | `http://username@host/`          | `""`                |
   * | `http://username:password@host/` | `"password"`        |
   * | `http://a%20b:c%20d@host/`       | `"c%20d"`           |
   */
  val encodedPassword: String

  /**
   * The number of segments in this URL's path. This is also the number of slashes in this URL's
   * path, like 3 in `http://host/a/b/c`. This is always at least 1.
   *
   * | URL                  | `pathSize()` |
   * | :------------------- | :----------- |
   * | `http://host/`       | `1`          |
   * | `http://host/a/b/c`  | `3`          |
   * | `http://host/a/b/c/` | `4`          |
   */
  val pathSize: Int

  /**
   * The entire path of this URL encoded for use in HTTP resource resolution. The returned path will
   * start with `"/"`.
   *
   * | URL                     | `encodedPath()` |
   * | :---------------------- | :-------------- |
   * | `http://host/`          | `"/"`           |
   * | `http://host/a/b/c`     | `"/a/b/c"`      |
   * | `http://host/a/b%20c/d` | `"/a/b%20c/d"`  |
   */
  val encodedPath: String

  /**
   * A list of encoded path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This
   * list is never empty though it may contain a single empty string.
   *
   * | URL                     | `encodedPathSegments()` |
   * | :---------------------- | :---------------------- |
   * | `http://host/`          | `[""]`                  |
   * | `http://host/a/b/c`     | `["a", "b", "c"]`       |
   * | `http://host/a/b%20c/d` | `["a", "b%20c", "d"]`   |
   */
  val encodedPathSegments: List<String>

  /**
   * The query of this URL, encoded for use in HTTP resource resolution. This string may be null
   * (for URLs with no query), empty (for URLs with an empty query) or non-empty (all other URLs).
   *
   * | URL                               | `encodedQuery()`       |
   * | :-------------------------------- | :--------------------- |
   * | `http://host/`                    | null                   |
   * | `http://host/?`                   | `""`                   |
   * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key+lime"` |
   * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
   * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
   */
  val encodedQuery: String?

  /**
   * This URL's query, like `"abc"` for `http://host/?abc`. Most callers should prefer
   * [queryParameterName] and [queryParameterValue] because these methods offer direct access to
   * individual query parameters.
   *
   * | URL                               | `query()`              |
   * | :-------------------------------- | :--------------------- |
   * | `http://host/`                    | null                   |
   * | `http://host/?`                   | `""`                   |
   * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key lime"` |
   * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
   * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
   */
  val query: String?

  /**
   * The number of query parameters in this URL, like 2 for `http://host/?a=apple&b=banana`. If this
   * URL has no query this is 0. Otherwise it is one more than the number of `"&"` separators in the
   * query.
   *
   * | URL                               | `querySize()` |
   * | :-------------------------------- | :------------ |
   * | `http://host/`                    | `0`           |
   * | `http://host/?`                   | `1`           |
   * | `http://host/?a=apple&k=key+lime` | `2`           |
   * | `http://host/?a=apple&a=apricot`  | `2`           |
   * | `http://host/?a=apple&b`          | `2`           |
   */
  val querySize: Int

  /**
   * The first query parameter named `name` decoded using UTF-8, or null if there is no such query
   * parameter.
   *
   * | URL                               | `queryParameter("a")` |
   * | :-------------------------------- | :-------------------- |
   * | `http://host/`                    | null                  |
   * | `http://host/?`                   | null                  |
   * | `http://host/?a=apple&k=key+lime` | `"apple"`             |
   * | `http://host/?a=apple&a=apricot`  | `"apple"`             |
   * | `http://host/?a=apple&b`          | `"apple"`             |
   */
  fun queryParameter(name: String): String?

  /**
   * The distinct query parameter names in this URL, like `["a", "b"]` for
   * `http://host/?a=apple&b=banana`. If this URL has no query this is the empty set.
   *
   * | URL                               | `queryParameterNames()` |
   * | :-------------------------------- | :---------------------- |
   * | `http://host/`                    | `[]`                    |
   * | `http://host/?`                   | `[""]`                  |
   * | `http://host/?a=apple&k=key+lime` | `["a", "k"]`            |
   * | `http://host/?a=apple&a=apricot`  | `["a"]`                 |
   * | `http://host/?a=apple&b`          | `["a", "b"]`            |
   */
  val queryParameterNames: Set<String>

  internal val url: String

  internal val queryNamesAndValues: List<String?>?

  /**
   * Returns all values for the query parameter `name` ordered by their appearance in this
   * URL. For example this returns `["banana"]` for `queryParameterValue("b")` on
   * `http://host/?a=apple&b=banana`.
   *
   * | URL                               | `queryParameterValues("a")` | `queryParameterValues("b")` |
   * | :-------------------------------- | :-------------------------- | :-------------------------- |
   * | `http://host/`                    | `[]`                        | `[]`                        |
   * | `http://host/?`                   | `[]`                        | `[]`                        |
   * | `http://host/?a=apple&k=key+lime` | `["apple"]`                 | `[]`                        |
   * | `http://host/?a=apple&a=apricot`  | `["apple", "apricot"]`      | `[]`                        |
   * | `http://host/?a=apple&b`          | `["apple"]`                 | `[null]`                    |
   */
  fun queryParameterValues(name: String): List<String?>

  /**
   * Returns the name of the query parameter at `index`. For example this returns `"a"`
   * for `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if
   * `index` is not less than the [query size][querySize].
   *
   * | URL                               | `queryParameterName(0)` | `queryParameterName(1)` |
   * | :-------------------------------- | :---------------------- | :---------------------- |
   * | `http://host/`                    | exception               | exception               |
   * | `http://host/?`                   | `""`                    | exception               |
   * | `http://host/?a=apple&k=key+lime` | `"a"`                   | `"k"`                   |
   * | `http://host/?a=apple&a=apricot`  | `"a"`                   | `"a"`                   |
   * | `http://host/?a=apple&b`          | `"a"`                   | `"b"`                   |
   */
  fun queryParameterName(index: Int): String

  /**
   * Returns the value of the query parameter at `index`. For example this returns `"apple"` for
   * `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if `index` is not less
   * than the [query size][querySize].
   *
   * | URL                               | `queryParameterValue(0)` | `queryParameterValue(1)` |
   * | :-------------------------------- | :----------------------- | :----------------------- |
   * | `http://host/`                    | exception                | exception                |
   * | `http://host/?`                   | null                     | exception                |
   * | `http://host/?a=apple&k=key+lime` | `"apple"`                | `"key lime"`             |
   * | `http://host/?a=apple&a=apricot`  | `"apple"`                | `"apricot"`              |
   * | `http://host/?a=apple&b`          | `"apple"`                | null                     |
   */
  fun queryParameterValue(index: Int): String?

  /**
   * This URL's encoded fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has
   * no fragment.
   *
   * | URL                    | `encodedFragment()` |
   * | :--------------------- | :------------------ |
   * | `http://host/`         | null                |
   * | `http://host/#`        | `""`                |
   * | `http://host/#abc`     | `"abc"`             |
   * | `http://host/#abc|def` | `"abc|def"`         |
   */
  @get:JvmName("encodedFragment") val encodedFragment: String?

  /**
   * Returns a string with containing this URL with its username, password, query, and fragment
   * stripped, and its path replaced with `/...`. For example, redacting
   * `http://username:password@example.com/path` returns `http://example.com/...`.
   */
  fun redact(): String

  /**
   * Returns the URL that would be retrieved by following `link` from this URL, or null if the
   * resulting URL is not well-formed.
   */
  fun resolve(link: String): HttpUrl?

  /**
   * Returns a builder based on this URL.
   */
  fun newBuilder(): Builder

  /**
   * Returns a builder for the URL that would be retrieved by following `link` from this URL,
   * or null if the resulting URL is not well-formed.
   */
  fun newBuilder(link: String): Builder?

  class Builder constructor() {
    internal var scheme: String?
    internal var encodedUsername: String
    internal var encodedPassword: String
    internal var host: String?
    internal var port: Int
    internal val encodedPathSegments: MutableList<String>
    internal var encodedQueryNamesAndValues: MutableList<String?>?
    internal var encodedFragment: String?

    fun scheme(scheme: String): Builder
    fun username(username: String): Builder

    fun encodedUsername(encodedUsername: String): Builder

    fun password(password: String): Builder

    fun encodedPassword(encodedPassword: String): Builder

    fun host(host: String): Builder
    fun port(port: Int): Builder

    fun addPathSegment(pathSegment: String): Builder

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
     * starts with a slash, the resulting URL will have empty path segment.
     */
    fun addPathSegments(pathSegments: String): Builder

    fun addEncodedPathSegment(encodedPathSegment: String): Builder

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
     * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
     */
    fun addEncodedPathSegments(encodedPathSegments: String): Builder

    fun setPathSegment(index: Int, pathSegment: String): Builder

    fun setEncodedPathSegment(index: Int, encodedPathSegment: String): Builder

    fun removePathSegment(index: Int): Builder

    fun encodedPath(encodedPath: String): Builder

    fun query(query: String?): Builder

    fun encodedQuery(encodedQuery: String?): Builder

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    fun addQueryParameter(name: String, value: String?): Builder

    /** Adds the pre-encoded query parameter to this URL's query string. */
    fun addEncodedQueryParameter(encodedName: String, encodedValue: String?): Builder

    fun setQueryParameter(name: String, value: String?): Builder

    fun setEncodedQueryParameter(encodedName: String, encodedValue: String?): Builder

    fun removeAllQueryParameters(name: String): Builder

    fun removeAllEncodedQueryParameters(encodedName: String): Builder

    fun fragment(fragment: String?): Builder

    fun encodedFragment(encodedFragment: String?): Builder

    fun build(): HttpUrl

    internal fun parse(base: HttpUrl?, input: String): Builder

  }

  companion object {

    fun defaultPort(scheme: String): Int

    /**
     * Returns a new [HttpUrl] representing this.
     *
     * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
     */
    fun String.toHttpUrl(): HttpUrl

    /**
     * Returns a new `HttpUrl` representing `url` if it is a well-formed HTTP or HTTPS URL, or null
     * if it isn't.
     */
    fun String.toHttpUrlOrNull(): HttpUrl?
  }
}
