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

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import okhttp3.internal.CommonHttpUrl.FRAGMENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.PATH_SEGMENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.QUERY_COMPONENT_ENCODE_SET_URI
import okhttp3.internal.CommonHttpUrl.commonAddEncodedPathSegment
import okhttp3.internal.CommonHttpUrl.commonAddEncodedPathSegments
import okhttp3.internal.CommonHttpUrl.commonAddEncodedQueryParameter
import okhttp3.internal.CommonHttpUrl.commonAddPathSegment
import okhttp3.internal.CommonHttpUrl.commonAddPathSegments
import okhttp3.internal.CommonHttpUrl.commonAddQueryParameter
import okhttp3.internal.CommonHttpUrl.commonBuild
import okhttp3.internal.CommonHttpUrl.commonDefaultPort
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
import okhttp3.internal.HttpUrlCommon.canonicalize
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.publicsuffix.PublicSuffixDatabase

actual class HttpUrl internal actual constructor(
  @get:JvmName("scheme") actual val scheme: String,

  @get:JvmName("username") actual val username: String,

  @get:JvmName("password") actual val password: String,

  @get:JvmName("host") actual val host: String,

  @get:JvmName("port") actual val port: Int,

  @get:JvmName("pathSegments") actual val pathSegments: List<String>,

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

    actual fun scheme(scheme: String) = commonScheme(scheme)

    actual fun username(username: String) = commonUsername(username)

    actual fun encodedUsername(encodedUsername: String) = commonEncodedUsername(encodedUsername)

    actual fun password(password: String) = commonPassword(password)

    actual fun encodedPassword(encodedPassword: String) = commonEncodedPassword(encodedPassword)

    actual fun host(host: String) = commonHost(host)

    actual fun port(port: Int) = commonPort(port)

    actual fun addPathSegment(pathSegment: String) = commonAddPathSegment(pathSegment)

    actual fun addPathSegments(pathSegments: String): Builder = commonAddPathSegments(pathSegments)

    actual fun addEncodedPathSegment(encodedPathSegment: String) = commonAddEncodedPathSegment(encodedPathSegment)

    actual fun addEncodedPathSegments(encodedPathSegments: String): Builder = commonAddEncodedPathSegments(encodedPathSegments)

    actual fun setPathSegment(index: Int, pathSegment: String) = commonSetPathSegment(index, pathSegment)

    actual fun setEncodedPathSegment(index: Int, encodedPathSegment: String) = commonSetEncodedPathSegment(index, encodedPathSegment)

    actual fun removePathSegment(index: Int) = commonRemovePathSegment(index)

    actual fun encodedPath(encodedPath: String) = commonEncodedPath(encodedPath)

    actual fun query(query: String?) = commonQuery(query)

    actual fun encodedQuery(encodedQuery: String?) = commonEncodedQuery(encodedQuery)

    actual fun addQueryParameter(name: String, value: String?) = commonAddQueryParameter(name, value)

    actual fun addEncodedQueryParameter(encodedName: String, encodedValue: String?) = commonAddEncodedQueryParameter(encodedName, encodedValue)

    actual fun setQueryParameter(name: String, value: String?) = commonSetQueryParameter(name, value)

    actual fun setEncodedQueryParameter(encodedName: String, encodedValue: String?) = commonSetEncodedQueryParameter(encodedName, encodedValue)

    actual fun removeAllQueryParameters(name: String) = commonRemoveAllQueryParameters(name)

    actual fun removeAllEncodedQueryParameters(encodedName: String) = commonRemoveAllEncodedQueryParameters(encodedName)

    actual fun fragment(fragment: String?) = commonFragment(fragment)

    actual fun encodedFragment(encodedFragment: String?) = commonEncodedFragment(encodedFragment)

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

    actual fun build(): HttpUrl = commonBuild()

    override fun toString(): String = commonToString()

    internal actual fun parse(base: HttpUrl?, input: String): Builder = commonParse(base, input)
  }

  actual companion object {
    @JvmStatic
    actual fun defaultPort(scheme: String): Int = commonDefaultPort(scheme)

    @JvmStatic
    @JvmName("get") actual fun String.toHttpUrl(): HttpUrl = commonToHttpUrl()

    @JvmStatic
    @JvmName("parse")
    actual fun String.toHttpUrlOrNull(): HttpUrl? = commonToHttpUrlOrNull()

    /**
     * Returns an [HttpUrl] for this if its protocol is `http` or `https`, or null if it has any
     * other protocol.
     */
    @JvmStatic
    @JvmName("get")
    fun URL.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmStatic
    @JvmName("get")
    fun URI.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "url.toHttpUrl()",
        imports = ["okhttp3.HttpUrl.Companion.toHttpUrl"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun get(url: String): HttpUrl = url.toHttpUrl()

    @JvmName("-deprecated_parse")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "url.toHttpUrlOrNull()",
        imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun parse(url: String): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "url.toHttpUrlOrNull()",
        imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun get(url: URL): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "uri.toHttpUrlOrNull()",
        imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun get(uri: URI): HttpUrl? = uri.toHttpUrlOrNull()
  }
}
