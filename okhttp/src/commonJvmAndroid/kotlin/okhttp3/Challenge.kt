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
import java.util.Collections.singletonMap
import java.util.Locale.US
import kotlin.text.Charsets.ISO_8859_1
import okhttp3.internal.commonEquals
import okhttp3.internal.commonHashCode
import okhttp3.internal.commonToString
import okhttp3.internal.unmodifiable

/**
 * An [RFC 7235][rfc_7235] challenge.
 *
 * [rfc_7235]: https://tools.ietf.org/html/rfc7235
 */
class Challenge(
  /** Returns the authentication scheme, like `Basic`. */
  @get:JvmName("scheme") val scheme: String,
  authParams: Map<String?, String>,
) {
  /**
   * Returns the auth params, including [realm] and [charset] if present, but as
   * strings. The map's keys are lowercase and should be treated case-insensitively.
   */
  @get:JvmName("authParams")
  val authParams: Map<String?, String>

  /** Returns the protection space. */
  @get:JvmName("realm")
  val realm: String?
    get() = authParams["realm"]

  /** The charset that should be used to encode the credentials. */
  @get:JvmName("charset")
  val charset: Charset
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
      val newKey = key?.lowercase(US)
      newAuthParams[newKey] = value
    }
    this.authParams = newAuthParams.unmodifiable()
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
    level = DeprecationLevel.ERROR,
  )
  fun scheme(): String = scheme

  @JvmName("-deprecated_authParams")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "authParams"),
    level = DeprecationLevel.ERROR,
  )
  fun authParams(): Map<String?, String> = authParams

  @JvmName("-deprecated_realm")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "realm"),
    level = DeprecationLevel.ERROR,
  )
  fun realm(): String? = realm

  @JvmName("-deprecated_charset")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "charset"),
    level = DeprecationLevel.ERROR,
  )
  fun charset(): Charset = charset

  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  override fun toString(): String = commonToString()
}
