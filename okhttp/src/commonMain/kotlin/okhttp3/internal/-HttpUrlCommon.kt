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

expect object HttpUrlCommon {

}

object CommonHttpUrl {
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
}
