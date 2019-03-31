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

/** An RFC 7235 challenge. */
class Challenge(
  private val scheme: String,
  authParams: Map<String?, String>
) {
  private val authParams: Map<String?, String>

  init {
    val newAuthParams: MutableMap<String?, String> = LinkedHashMap()
    for ((key, value) in authParams) {
      val newKey = key?.toLowerCase(US)
      newAuthParams[newKey] = value
    }
    this.authParams = unmodifiableMap<String?, String>(newAuthParams)
  }

  constructor(scheme: String, realm: String) : this(scheme, singletonMap("realm", realm))

  /** Returns a copy of this charset that expects a credential encoded with [charset]. */
  fun withCharset(charset: Charset): Challenge {
    val authParams = LinkedHashMap(this.authParams)
    authParams["charset"] = charset.name()
    return Challenge(scheme, authParams)
  }

  /** Returns the authentication scheme, like `Basic`. */
  fun scheme() = scheme

  /**
   * Returns the auth params, including [realm] and [charset] if present, but as
   * strings. The map's keys are lowercase and should be treated case-insensitively.
   */
  fun authParams() = authParams

  /** Returns the protection space. */
  fun realm(): String? = authParams["realm"]

  /** Returns the charset that should be used to encode the credentials. */
  fun charset(): Charset {
    val charset = authParams["charset"]
    if (charset != null) {
      try {
        return Charset.forName(charset)
      } catch (ignore: Exception) {
      }
    }
    return ISO_8859_1
  }

  override fun equals(other: Any?): Boolean {
    return other is Challenge
        && other.scheme == scheme
        && other.authParams == authParams
  }

  override fun hashCode(): Int {
    var result = 29
    result = 31 * result + scheme.hashCode()
    result = 31 * result + authParams.hashCode()
    return result
  }

  override fun toString(): String {
    return "$scheme authParams=$authParams"
  }

}