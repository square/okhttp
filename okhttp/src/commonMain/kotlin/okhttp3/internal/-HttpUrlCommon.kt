/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.internal

import kotlin.jvm.JvmStatic
import okhttp3.HttpUrl
import okhttp3.internal.HttpUrlCommon.canonicalize
import okhttp3.internal.HttpUrlCommon.writePercentDecoded
import okio.Buffer

expect object HttpUrlCommon {
  internal fun Buffer.writePercentDecoded(
    encoded: String,
    pos: Int,
    limit: Int,
    plusIsSpace: Boolean
  )

  internal fun String.canonicalize(
    pos: Int = 0,
    limit: Int = length,
    encodeSet: String,
    alreadyEncoded: Boolean = false,
    strict: Boolean = false,
    plusIsSpace: Boolean = false,
    unicodeAllowed: Boolean = false,
  ): String

}

object CommonHttpUrl {

  internal val HEX_DIGITS =
    charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
  internal const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
  internal const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
  internal const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
  internal const val PATH_SEGMENT_ENCODE_SET_URI = "[]"
  internal const val QUERY_ENCODE_SET = " \"'<>#"
  internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="
  internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
  internal const val QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}"
  internal const val FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~"
  internal const val FRAGMENT_ENCODE_SET = ""
  internal const val FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}"

  val HttpUrl.commonEncodedUsername: String
    get() {
      if (username.isEmpty()) return ""
      val usernameStart = scheme.length + 3 // "://".length() == 3.
      val usernameEnd = url.delimiterOffset(":@", usernameStart, url.length)
      return url.substring(usernameStart, usernameEnd)
    }

  val HttpUrl.commonEncodedPassword: String
    get() {
      if (password.isEmpty()) return ""
      val passwordStart = url.indexOf(':', scheme.length + 3) + 1
      val passwordEnd = url.indexOf('@')
      return url.substring(passwordStart, passwordEnd)
    }

  val HttpUrl.commonPathSize: Int get() = pathSegments.size

  val HttpUrl.commonEncodedPath: String
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3) // "://".length() == 3.
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      return url.substring(pathStart, pathEnd)
    }

  val HttpUrl.commonEncodedPathSegments: List<String>
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3)
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      val result = mutableListOf<String>()
      var i = pathStart
      while (i < pathEnd) {
        i++ // Skip the '/'.
        val segmentEnd = url.delimiterOffset('/', i, pathEnd)
        result.add(url.substring(i, segmentEnd))
        i = segmentEnd
      }
      return result
    }

  val HttpUrl.commonEncodedQuery: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val queryStart = url.indexOf('?') + 1
      val queryEnd = url.delimiterOffset('#', queryStart, url.length)
      return url.substring(queryStart, queryEnd)
    }

  val HttpUrl.commonQuery: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val result = StringBuilder()
      queryNamesAndValues.toQueryString(result)
      return result.toString()
    }

  val HttpUrl.commonQuerySize: Int
    get() {
      return if (queryNamesAndValues != null) queryNamesAndValues.size / 2 else 0
    }

  fun HttpUrl.commonQueryParameter(name: String): String? {
    if (queryNamesAndValues == null) return null
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        return queryNamesAndValues[i + 1]
      }
    }
    return null
  }

  val HttpUrl.commonQueryParameterNames: Set<String>
    get() {
      if (queryNamesAndValues == null) return emptySet()
      val result = LinkedHashSet<String>()
      for (i in 0 until queryNamesAndValues.size step 2) {
        result.add(queryNamesAndValues[i]!!)
      }
      return result.readOnly()
    }

  fun HttpUrl.commonQueryParameterValues(name: String): List<String?> {
    if (queryNamesAndValues == null) return emptyList()
    val result = mutableListOf<String?>()
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        result.add(queryNamesAndValues[i + 1])
      }
    }
    return result.readOnly()
  }

  fun HttpUrl.commonQueryParameterName(index: Int): String {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2]!!
  }

  fun HttpUrl.commonQueryParameterValue(index: Int): String? {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2 + 1]
  }

  val HttpUrl.commonEncodedFragment: String?
    get() {
      if (fragment == null) return null
      val fragmentStart = url.indexOf('#') + 1
      return url.substring(fragmentStart)
    }

  /** Returns a string for this list of query names and values. */
  internal fun List<String?>.toQueryString(out: StringBuilder) {
    for (i in 0 until size step 2) {
      val name = this[i]
      val value = this[i + 1]
      if (i > 0) out.append('&')
      out.append(name)
      if (value != null) {
        out.append('=')
        out.append(value)
      }
    }
  }

  internal fun HttpUrl.commonRedact(): String {
    return newBuilder("/...")!!
      .username("")
      .password("")
      .build()
      .toString()
  }


  fun HttpUrl.commonResolve(link: String): HttpUrl? = newBuilder(link)?.build()

  fun HttpUrl.commonNewBuilder(): HttpUrl.Builder {
    val result = HttpUrl.Builder()
    result.scheme = scheme
    result.encodedUsername = encodedUsername
    result.encodedPassword = encodedPassword
    result.host = host
    // If we're set to a default port, unset it in case of a scheme change.
    result.port = if (port != commonDefaultPort(scheme)) port else -1
    result.encodedPathSegments.clear()
    result.encodedPathSegments.addAll(encodedPathSegments)
    result.encodedQuery(encodedQuery)
    result.encodedFragment = encodedFragment
    return result
  }

  fun HttpUrl.commonNewBuilder(link: String): HttpUrl.Builder? {
    return try {
      HttpUrl.Builder().parse(this, link)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  fun HttpUrl.commonEquals(other: Any?): Boolean {
    return other is HttpUrl && other.url == url
  }

  fun HttpUrl.commonHashCode(): Int = url.hashCode()

  fun HttpUrl.commonToString(): String = url


  /** Returns 80 if `scheme.equals("http")`, 443 if `scheme.equals("https")` and -1 otherwise. */
  @JvmStatic
  fun commonDefaultPort(scheme: String): Int {
    return when (scheme) {
      "http" -> 80
      "https" -> 443
      else -> -1
    }
  }


  /**
   * @param scheme either "http" or "https".
   */
  fun HttpUrl.Builder.commonScheme(scheme: String) = apply {
    when {
      scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
      scheme.equals("https", ignoreCase = true) -> this.scheme = "https"
      else -> throw IllegalArgumentException("unexpected scheme: $scheme")
    }
  }

  fun HttpUrl.Builder.commonUsername(username: String) = apply {
    this.encodedUsername = username.canonicalize(encodeSet = USERNAME_ENCODE_SET)
  }

  fun HttpUrl.Builder.commonEncodedUsername(encodedUsername: String) = apply {
    this.encodedUsername = encodedUsername.canonicalize(
      encodeSet = USERNAME_ENCODE_SET,
      alreadyEncoded = true
    )
  }

  fun HttpUrl.Builder.commonPassword(password: String) = apply {
    this.encodedPassword = password.canonicalize(encodeSet = PASSWORD_ENCODE_SET)
  }

  fun HttpUrl.Builder.commonEncodedPassword(encodedPassword: String) = apply {
    this.encodedPassword = encodedPassword.canonicalize(
      encodeSet = PASSWORD_ENCODE_SET,
      alreadyEncoded = true
    )
  }

  /**
   * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
   * address.
   */
  fun HttpUrl.Builder.commonHost(host: String) = apply {
    val encoded = host.percentDecode().toCanonicalHost() ?: throw IllegalArgumentException(
      "unexpected host: $host")
    this.host = encoded
  }

  internal fun String.percentDecode(
    pos: Int = 0,
    limit: Int = length,
    plusIsSpace: Boolean = false
  ): String {
    for (i in pos until limit) {
      val c = this[i]
      if (c == '%' || c == '+' && plusIsSpace) {
        // Slow path: the character at i requires decoding!
        val out = Buffer()
        out.writeUtf8(this, pos, i)
        out.writePercentDecoded(this, pos = i, limit = limit, plusIsSpace = plusIsSpace)
        return out.readUtf8()
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return substring(pos, limit)
  }

  fun HttpUrl.Builder.commonPort(port: Int) = apply {
    require(port in 1..65535) { "unexpected port: $port" }
    this.port = port
  }
}
