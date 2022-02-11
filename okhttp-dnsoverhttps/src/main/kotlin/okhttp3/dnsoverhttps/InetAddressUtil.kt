/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.dnsoverhttps

import java.lang.IllegalArgumentException
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*

object InetAddressUtil {
    private const val IPV4_PART_COUNT = 4
    private const val IPV6_PART_COUNT = 8

    fun forString(ipString: String): InetAddress {
        val address = textToNumericFormatV4(ipString) ?: textToNumericFormatV6(ipString)
        ?: throw IllegalArgumentException("$ipString is not an IP string literal")

        try {
            return InetAddress.getByAddress(address)
        } catch (e: UnknownHostException) {
            throw IllegalArgumentException("$ipString is extremely broken.")
        }
    }

    private fun textToNumericFormatV4(ipString: String): ByteArray? {
        var isIpv6 = false
        val address = when {
            ipString.uppercase(Locale.US).startsWith("::FFFF:") -> ipString.substring(7)
            ipString.startsWith("::") -> {
                isIpv6 = true
                ipString.substring(2)
            }
            else -> ipString
        }
        val addresses = address.split(".")
        if (addresses.size != IPV4_PART_COUNT) {
            return null
        }

        try {
            val bytes = ByteArray(IPV4_PART_COUNT)
            for (i in bytes.indices) {
                val segment = addresses[i]
                val piece = Integer.parseInt(segment)
                if (piece < 0 || piece > 255) {
                    return null
                }
                if (segment.startsWith("0") && segment.length != 1) {
                    return null
                }
                bytes[i] = piece.toByte()
            }
            return if (isIpv6) {
                val data = ByteArray(IPV6_PART_COUNT * 2)
                System.arraycopy(bytes, 0, data, 12, IPV4_PART_COUNT)
                data
            } else {
                bytes
            }
        } catch (e: NumberFormatException) {
            return null
        }
    }

    private fun textToNumericFormatV6(ipString: String): ByteArray? {
        if (!ipString.contains(":")) {
            return null
        }
        if (ipString.contains(":::")) {
            return null
        }

        val address = padIpString(if (ipString.contains(".")) {
            convertDottedQuadToHex(ipString) ?: return null
        } else ipString)

        try {
            val addresses = address.split(":", limit = IPV6_PART_COUNT)
            if (addresses.size != IPV6_PART_COUNT) {
                return null
            }
            val bytes = ByteArray(IPV6_PART_COUNT * 2)
            for (i in 0 until IPV6_PART_COUNT) {
                val piece = if (addresses[i] == "") 0 else Integer.parseInt(addresses[i], 16)
                bytes[2 * i] = ((piece and 0xFF00) ushr 8).toByte()
                bytes[2 * i + 1] = (piece and 0xFF).toByte()
            }
            return bytes
        } catch (e: NumberFormatException) {
            return null
        }
    }

    private fun padIpString(ipString: String): String {
        return if (ipString.contains("::")) {
            val count = ipString.toCharArray().count { it == ':' }
            val buffer = StringBuilder("::")
            for (i in 0 until (7 - count)) {
                buffer.append(":")
            }
            ipString.replace("::", buffer.toString())
        } else ipString
    }

    private fun convertDottedQuadToHex(ipString: String): String? {
        val lastColon = ipString.lastIndexOf(":")
        val initialPart = ipString.substring(0, lastColon + 1)
        val dottedPart = ipString.substring(lastColon + 1)
        val quad = textToNumericFormatV4(dottedPart) ?: return null

        val penultimate = Integer.toHexString((quad[0].toInt() shl 8) or quad[1].toInt())
        val ultimate = Integer.toHexString((quad[2].toInt() shl 8) or quad[3].toInt())
        return "$initialPart$penultimate:$ultimate"
    }
}