/*
 * Copyright (C) 2021 Square, Inc.
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import okhttp3.internal.idn.IDNA_MAPPING_TABLE
import okhttp3.internal.idn.Punycode
import okio.Buffer

/**
 * Quick and dirty pattern to differentiate IP addresses from hostnames. This is an approximation
 * of Android's private InetAddress#isNumeric API.
 *
 * This matches IPv6 addresses as a hex string containing at least one colon, and possibly
 * including dots after the first colon. It matches IPv4 addresses as strings containing only
 * decimal digits and dots. This pattern matches strings like "a:.23" and "54" that are neither IP
 * addresses nor hostnames; they will be verified as IP addresses (which is a more strict
 * verification).
 */
private val VERIFY_AS_IP_ADDRESS = "([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)".toRegex()

/** Returns true if this string is not a host name and might be an IP address. */
fun String.canParseAsIpAddress(): Boolean = VERIFY_AS_IP_ADDRESS.matches(this)

/**
 * Returns true if the length is not valid for DNS (empty or greater than 253 characters), or if any
 * label is longer than 63 characters. Trailing dots are okay.
 */
internal fun String.containsInvalidLabelLengths(): Boolean {
  if (length !in 1..253) return true

  var labelStart = 0
  while (true) {
    val dot = indexOf('.', startIndex = labelStart)
    val labelLength =
      when (dot) {
        -1 -> length - labelStart
        else -> dot - labelStart
      }
    if (labelLength !in 1..63) return true
    if (dot == -1) break
    if (dot == length - 1) break // Trailing '.' is allowed.
    labelStart = dot + 1
  }

  return false
}

internal fun String.containsInvalidHostnameAsciiCodes(): Boolean {
  for (i in 0 until length) {
    val c = this[i]
    // The WHATWG Host parsing rules accepts some character codes which are invalid by
    // definition for OkHttp's host header checks (and the WHATWG Host syntax definition). Here
    // we rule out characters that would cause problems in host headers.
    if (c <= '\u001f' || c >= '\u007f') {
      return true
    }
    // Check for the characters mentioned in the WHATWG Host parsing spec:
    // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
    // (excluding the characters covered above).
    if (" #%/:?@[\\]".indexOf(c) != -1) {
      return true
    }
  }
  return false
}

/** Decodes an IPv6 address like 1111:2222:3333:4444:5555:6666:7777:8888 or ::1. */
internal fun decodeIpv6(
  input: String,
  pos: Int,
  limit: Int,
): ByteArray? {
  val address = ByteArray(16)
  var b = 0
  var compress = -1
  var groupOffset = -1

  var i = pos
  while (i < limit) {
    if (b == address.size) return null // Too many groups.

    // Read a delimiter.
    if (i + 2 <= limit && input.startsWith("::", startIndex = i)) {
      // Compression "::" delimiter, which is anywhere in the input, including its prefix.
      if (compress != -1) return null // Multiple "::" delimiters.
      i += 2
      b += 2
      compress = b
      if (i == limit) break
    } else if (b != 0) {
      // Group separator ":" delimiter.
      if (input.startsWith(":", startIndex = i)) {
        i++
      } else if (input.startsWith(".", startIndex = i)) {
        // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
        if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null
        b += 2 // We rewound two bytes and then added four.
        break
      } else {
        return null // Wrong delimiter.
      }
    }

    // Read a group, one to four hex digits.
    var value = 0
    groupOffset = i
    while (i < limit) {
      val hexDigit = input[i].parseHexDigit()
      if (hexDigit == -1) break
      value = (value shl 4) + hexDigit
      i++
    }
    val groupLength = i - groupOffset
    if (groupLength == 0 || groupLength > 4) return null // Group is the wrong size.

    // We've successfully read a group. Assign its value to our byte array.
    address[b++] = (value.ushr(8) and 0xff).toByte()
    address[b++] = (value and 0xff).toByte()
  }

  // All done. If compression happened, we need to move bytes to the right place in the
  // address. Here's a sample:
  //
  //      input: "1111:2222:3333::7777:8888"
  //     before: { 11, 11, 22, 22, 33, 33, 00, 00, 77, 77, 88, 88, 00, 00, 00, 00  }
  //   compress: 6
  //          b: 10
  //      after: { 11, 11, 22, 22, 33, 33, 00, 00, 00, 00, 00, 00, 77, 77, 88, 88 }
  //
  if (b != address.size) {
    if (compress == -1) return null // Address didn't have compression or enough groups.
    address.copyInto(address, address.size - (b - compress), compress, b)
    address.fill(0.toByte(), compress, compress + (address.size - b))
  }

  return address
}

/** Decodes an IPv4 address suffix of an IPv6 address, like 1111::5555:6666:192.168.0.1. */
internal fun decodeIpv4Suffix(
  input: String,
  pos: Int,
  limit: Int,
  address: ByteArray,
  addressOffset: Int,
): Boolean {
  var b = addressOffset

  var i = pos
  while (i < limit) {
    if (b == address.size) return false // Too many groups.

    // Read a delimiter.
    if (b != addressOffset) {
      if (input[i] != '.') return false // Wrong delimiter.
      i++
    }

    // Read 1 or more decimal digits for a value in 0..255.
    var value = 0
    val groupOffset = i
    while (i < limit) {
      val c = input[i]
      if (c < '0' || c > '9') break
      if (value == 0 && groupOffset != i) return false // Reject unnecessary leading '0's.
      value = value * 10 + c.code - '0'.code
      if (value > 255) return false // Value out of range.
      i++
    }
    val groupLength = i - groupOffset
    if (groupLength == 0) return false // No digits.

    // We've successfully read a byte.
    address[b++] = value.toByte()
  }

  // Check for too few groups. We wanted exactly four.
  return b == addressOffset + 4
}

/** Encodes an IPv6 address in canonical form according to RFC 5952. */
internal fun inet6AddressToAscii(address: ByteArray): String {
  // Go through the address looking for the longest run of 0s. Each group is 2-bytes.
  // A run must be longer than one group (section 4.2.2).
  // If there are multiple equal runs, the first one must be used (section 4.2.3).
  var longestRunOffset = -1
  var longestRunLength = 0
  run {
    var i = 0
    while (i < address.size) {
      val currentRunOffset = i
      while (i < 16 && address[i].toInt() == 0 && address[i + 1].toInt() == 0) {
        i += 2
      }
      val currentRunLength = i - currentRunOffset
      if (currentRunLength > longestRunLength && currentRunLength >= 4) {
        longestRunOffset = currentRunOffset
        longestRunLength = currentRunLength
      }
      i += 2
    }
  }

  // Emit each 2-byte group in hex, separated by ':'. The longest run of zeroes is "::".
  val result = Buffer()
  var i = 0
  while (i < address.size) {
    if (i == longestRunOffset) {
      result.writeByte(':'.code)
      i += longestRunLength
      if (i == 16) result.writeByte(':'.code)
    } else {
      if (i > 0) result.writeByte(':'.code)
      val group = address[i] and 0xff shl 8 or (address[i + 1] and 0xff)
      result.writeHexadecimalUnsignedLong(group.toLong())
      i += 2
    }
  }
  return result.readUtf8()
}

/**
 * Returns the canonical address for [address]. If [address] is an IPv6 address that is mapped to an
 * IPv4 address, this returns the IPv4-mapped address. Otherwise, this returns [address].
 *
 * https://en.wikipedia.org/wiki/IPv6#IPv4-mapped_IPv6_addresses
 */
internal fun canonicalizeInetAddress(address: ByteArray): ByteArray {
  return when {
    isMappedIpv4Address(address) -> address.sliceArray(12 until 16)
    else -> address
  }
}

/** Returns true for IPv6 addresses like `0000:0000:0000:0000:0000:ffff:XXXX:XXXX`. */
private fun isMappedIpv4Address(address: ByteArray): Boolean {
  if (address.size != 16) return false

  for (i in 0 until 10) {
    if (address[i] != 0.toByte()) return false
  }

  if (address[10] != 255.toByte()) return false
  if (address[11] != 255.toByte()) return false

  return true
}

/** Encodes an IPv4 address in canonical form according to RFC 4001. */
internal fun inet4AddressToAscii(address: ByteArray): String {
  require(address.size == 4)
  return Buffer()
    .writeDecimalLong((address[0] and 0xff).toLong())
    .writeByte('.'.code)
    .writeDecimalLong((address[1] and 0xff).toLong())
    .writeByte('.'.code)
    .writeDecimalLong((address[2] and 0xff).toLong())
    .writeByte('.'.code)
    .writeDecimalLong((address[3] and 0xff).toLong())
    .readUtf8()
}

/**
 * If this is an IP address, this returns the IP address in canonical form.
 *
 * Otherwise, this performs IDN ToASCII encoding and canonicalize the result to lowercase. For
 * example this converts `☃.net` to `xn--n3h.net`, and `WwW.GoOgLe.cOm` to `www.google.com`.
 * `null` will be returned if the host cannot be ToASCII encoded or if the result contains
 * unsupported ASCII characters.
 */
internal fun String.toCanonicalHost(): String? {
  val host: String = this

  // If the input contains a :, it’s an IPv6 address.
  if (":" in host) {
    // If the input is encased in square braces "[...]", drop 'em.
    val inetAddressByteArray =
      (
        if (host.startsWith("[") && host.endsWith("]")) {
          decodeIpv6(host, 1, host.length - 1)
        } else {
          decodeIpv6(host, 0, host.length)
        }
      ) ?: return null

    val address = canonicalizeInetAddress(inetAddressByteArray)
    if (address.size == 16) return inet6AddressToAscii(address)
    if (address.size == 4) return inet4AddressToAscii(address) // An IPv4-mapped IPv6 address.
    throw AssertionError("Invalid IPv6 address: '$host'")
  }

  val result = idnToAscii(host) ?: return null
  if (result.isEmpty()) return null
  if (result.containsInvalidHostnameAsciiCodes()) return null
  if (result.containsInvalidLabelLengths()) return null

  return result
}

internal fun idnToAscii(host: String): String? {
  val bufferA = Buffer().writeUtf8(host)
  val bufferB = Buffer()

  // 1. Map, from bufferA to bufferB.
  while (!bufferA.exhausted()) {
    val codePoint = bufferA.readUtf8CodePoint()
    if (!IDNA_MAPPING_TABLE.map(codePoint, bufferB)) return null
  }

  // 2. Normalize, from bufferB to bufferA.
  val normalized = normalizeNfc(bufferB.readUtf8())
  bufferA.writeUtf8(normalized)

  // 3. For each label, convert/validate Punycode.
  val decoded = Punycode.decode(bufferA.readUtf8()) ?: return null

  // 4.1 Validate.

  // Must be NFC.
  if (decoded != normalizeNfc(decoded)) return null

  // TODO: Must not begin with a combining mark.
  // TODO: Each character must be 'valid' or 'deviation'. Not mapped.
  // TODO: CheckJoiners from IDNA 2008
  // TODO: CheckBidi from IDNA 2008, RFC 5893, Section 2.

  return Punycode.encode(decoded)
}
