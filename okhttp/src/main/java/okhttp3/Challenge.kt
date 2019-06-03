/*
 * Copyright (C) 2014 Square, Inc.
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

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.Collections.singletonMap
import java.util.Collections.unmodifiableMap
import java.util.Locale.US

/**
 * An [RFC 7235][rfc_7235] challenge.
 *
 * [rfc_7235]: https://tools.ietf.org/html/rfc7235
 */
class Challenge(
  /** Returns the authentication scheme, like `Basic`. */
  @get:JvmName("scheme") val scheme: String,

  authParams: Map<String?, String>
) {
  /**
   * Returns the auth params, including [realm] and [charset] if present, but as
   * strings. The map's keys are lowercase and should be treated case-insensitively.
   */
  @get:JvmName("authParams") val authParams: Map<String?, String>

  /** Returns the protection space. */
  @get:JvmName("realm") val realm: String?
    get() = authParams["realm"]

  /** The charset that should be used to encode the credentials. */
  @get:JvmName("charset") val charset: Charset
    get() {
      val charset = authParams["charset"]
      if (charset != null) {
        try {
          return Charset.forName(charset)
        } catch (ignore: Exception) {
        }
      }
      return ISO_8859_1
    }

  constructor(scheme: String, realm: String) : this(scheme, singletonMap("realm", realm))

  init {
    val newAuthParams = mutableMapOf<String?, String>()
    for ((key, value) in authParams) {
      val newKey = key?.toLowerCase(US)
      newAuthParams[newKey] = value
    }
    this.authParams = unmodifiableMap<String?, String>(newAuthParams)
  }

  /** Returns a copy of this charset that expects a credential encoded with [charset]. */
  fun withCharset(charset: Charset): Challenge {
    val authParams = this.authParams.toMutableMap()
    authParams["charset"] = charset.name()
    return Challenge(scheme, authParams)
  }

  @JvmName("-deprecated_scheme")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "scheme"),
      level = DeprecationLevel.ERROR)
  fun scheme() = scheme

  @JvmName("-deprecated_authParams")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "authParams"),
      level = DeprecationLevel.ERROR)
  fun authParams() = authParams

  @JvmName("-deprecated_realm")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "realm"),
      level = DeprecationLevel.ERROR)
  fun realm(): String? = realm

  @JvmName("-deprecated_charset")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "charset"),
      level = DeprecationLevel.ERROR)
  fun charset(): Charset = charset

  override fun equals(other: Any?): Boolean {
    return other is Challenge &&
        other.scheme == scheme &&
        other.authParams == authParams
  }

  override fun hashCode(): Int {
    var result = 29
    result = 31 * result + scheme.hashCode()
    result = 31 * result + authParams.hashCode()
    return result
  }

  override fun toString() = "$scheme authParams=$authParams"
}