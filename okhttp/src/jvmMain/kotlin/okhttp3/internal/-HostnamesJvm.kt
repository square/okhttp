/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

/**
 * If this is an IP address, this returns the IP address in canonical form.
 *
 * Otherwise, this performs IDN ToASCII encoding and canonicalize the result to lowercase. For
 * example this converts `☃.net` to `xn--n3h.net`, and `WwW.GoOgLe.cOm` to `www.google.com`.
 * `null` will be returned if the host cannot be ToASCII encoded or if the result contains
 * unsupported ASCII characters.
 */
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
    val inetAddress = InetAddress.getByAddress(inetAddressByteArray)
    val address = inetAddress.address
    if (address.size == 16) return inet6AddressToAscii(address)
    if (address.size == 4) return inetAddress.hostAddress // An IPv4-mapped IPv6 address.
    throw AssertionError("Invalid IPv6 address: '$host'")
  }

  try {
    val result = IDN.toASCII(host).lowercase(Locale.US)
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
