/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package okhttp3.dnsoverhttps

import okio.Buffer
import okio.ByteString
import okio.utf8Size
import java.io.EOFException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/**
 * Trivial Dns Encoder/Decoder, basically ripped from Netty full implementation.
 */
object DnsRecordCodec {
  private const val SERVFAIL = 2
  private const val NXDOMAIN = 3
  const val TYPE_A = 0x0001
  const val TYPE_AAAA = 0x001c
  private const val TYPE_PTR = 0x000c
  private val ASCII = StandardCharsets.US_ASCII

  fun encodeQuery(host: String, type: Int): ByteString = Buffer().apply {
    writeShort(0) // query id
    writeShort(256) // flags with recursion
    writeShort(1) // question count
    writeShort(0) // answerCount
    writeShort(0) // authorityResourceCount
    writeShort(0) // additional

    val nameBuf = Buffer()
    val labels = host.split('.').dropLastWhile { it.isEmpty() }
    for (label in labels) {
      val utf8ByteCount = label.utf8Size()
      require(utf8ByteCount == label.length.toLong()) { "non-ascii hostname: $host" }
      nameBuf.writeByte(utf8ByteCount.toInt())
      nameBuf.writeUtf8(label)
    }
    nameBuf.writeByte(0) // end

    nameBuf.copyTo(this, 0, nameBuf.size)
    writeShort(type)
    writeShort(1) // CLASS_IN
  }.readByteString()

  @Throws(Exception::class)
  fun decodeAnswers(hostname: String, byteString: ByteString): List<InetAddress> {
    val result = mutableListOf<InetAddress>()

    val buf = Buffer()
    buf.write(byteString)
    buf.readShort() // query id

    val flags = buf.readShort().toInt() and 0xffff
    require(flags shr 15 != 0) { "not a response" }

    val responseCode = flags and 0xf

    if (responseCode == NXDOMAIN) {
      throw UnknownHostException("$hostname: NXDOMAIN")
    } else if (responseCode == SERVFAIL) {
      throw UnknownHostException("$hostname: SERVFAIL")
    }

    val questionCount = buf.readShort().toInt() and 0xffff
    val answerCount = buf.readShort().toInt() and 0xffff
    buf.readShort() // authority record count
    buf.readShort() // additional record count

    for (i in 0 until questionCount) {
      skipName(buf) // name
      buf.readShort() // type
      buf.readShort() // class
    }

    for (i in 0 until answerCount) {
      skipName(buf) // name

      val type = buf.readShort().toInt() and 0xffff
      buf.readShort() // class
      @Suppress("UNUSED_VARIABLE") val ttl = buf.readInt().toLong() and 0xffffffffL // ttl
      val length = buf.readShort().toInt() and 0xffff

      if (type == TYPE_A || type == TYPE_AAAA) {
        val bytes = ByteArray(length)
        buf.read(bytes)
        result.add(InetAddress.getByAddress(bytes))
      } else {
        buf.skip(length.toLong())
      }
    }

    return result
  }

  @Throws(EOFException::class)
  private fun skipName(source: Buffer) {
    // 0 - 63 bytes
    var length = source.readByte().toInt()

    if (length < 0) {
      // compressed name pointer, first two bits are 1
      // drop second byte of compression offset
      source.skip(1)
    } else {
      while (length > 0) {
        // skip each part of the domain name
        source.skip(length.toLong())
        length = source.readByte().toInt()
      }
    }
  }
}
