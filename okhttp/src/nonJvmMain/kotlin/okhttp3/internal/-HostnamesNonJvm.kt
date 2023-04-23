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

import com.squareup.okhttpicu.SYSTEM_NORMALIZER

internal actual fun String.toCanonicalHost(): String? {
  val host: String = this

  // If the input contains a :, it’s an IPv6 address.
  if (":" in host) {
    // If the input is encased in square braces "[...]", drop 'em.
    val inetAddressByteArray = (if (host.startsWith("[") && host.endsWith("]")) {
      decodeIpv6(host, 1, host.length - 1)
    } else {
      decodeIpv6(host, 0, host.length)
    }) ?: return null
    // TODO implement properly
    return inet6AddressToAscii(inetAddressByteArray)
  }

  try {
    val result = idnToAscii(host)
    if (result.isEmpty()) return null

    return if (result.containsInvalidHostnameAsciiCodes()) {
      // The IDN ToASCII result contains illegal characters.
      null
    } else if (result.containsInvalidLabelLengths()) {
      // The IDN ToASCII result contains invalid labels.
      null
    } else {
      result
    }
  } catch (_: IllegalArgumentException) {
    return null
  }
}

internal fun inet4AddressToAscii(address: ByteArray): String {
  return address.joinToString(".")
}

fun idnToAscii(host: String): String {
  // TODO implement properly
  return SYSTEM_NORMALIZER.normalizeNfc(host).lowercase()
}
