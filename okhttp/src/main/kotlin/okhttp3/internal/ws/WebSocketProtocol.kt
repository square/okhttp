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
package okhttp3.internal.ws

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

object WebSocketProtocol {
  /** Magic value which must be appended to the key in a response header. */
  internal const val ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /*
  Each frame starts with two bytes of data.

   0 1 2 3 4 5 6 7    0 1 2 3 4 5 6 7
  +-+-+-+-+-------+  +-+-------------+
  |F|R|R|R| OP    |  |M| LENGTH      |
  |I|S|S|S| CODE  |  |A|             |
  |N|V|V|V|       |  |S|             |
  | |1|2|3|       |  |K|             |
  +-+-+-+-+-------+  +-+-------------+
  */

  /** Byte 0 flag for whether this is the final fragment in a message. */
  internal const val B0_FLAG_FIN = 128
  /** Byte 0 reserved flag 1. Must be 0 unless negotiated otherwise. */
  internal const val B0_FLAG_RSV1 = 64
  /** Byte 0 reserved flag 2. Must be 0 unless negotiated otherwise. */
  internal const val B0_FLAG_RSV2 = 32
  /** Byte 0 reserved flag 3. Must be 0 unless negotiated otherwise. */
  internal const val B0_FLAG_RSV3 = 16
  /** Byte 0 mask for the frame opcode. */
  internal const val B0_MASK_OPCODE = 15
  /** Flag in the opcode which indicates a control frame. */
  internal const val OPCODE_FLAG_CONTROL = 8

  /**
   * Byte 1 flag for whether the payload data is masked.
   *
   * If this flag is set, the next four
   * bytes represent the mask key. These bytes appear after any additional bytes specified by [B1_MASK_LENGTH].
   */
  internal const val B1_FLAG_MASK = 128
  /**
   * Byte 1 mask for the payload length.
   *
   * If this value is [PAYLOAD_SHORT], the next two
   * bytes represent the length. If this value is [PAYLOAD_LONG], the next eight bytes
   * represent the length.
   */
  internal const val B1_MASK_LENGTH = 127

  internal const val OPCODE_CONTINUATION = 0x0
  internal const val OPCODE_TEXT = 0x1
  internal const val OPCODE_BINARY = 0x2

  internal const val OPCODE_CONTROL_CLOSE = 0x8
  internal const val OPCODE_CONTROL_PING = 0x9
  internal const val OPCODE_CONTROL_PONG = 0xa

  /**
   * Maximum length of frame payload. Larger payloads, if supported by the frame type, can use the
   * special values [PAYLOAD_SHORT] or [PAYLOAD_LONG].
   */
  internal const val PAYLOAD_BYTE_MAX = 125L
  /** Maximum length of close message in bytes. */
  internal const val CLOSE_MESSAGE_MAX = PAYLOAD_BYTE_MAX - 2
  /**
   * Value for [B1_MASK_LENGTH] which indicates the next two bytes are the unsigned length.
   */
  internal const val PAYLOAD_SHORT = 126
  /** Maximum length of a frame payload to be denoted as [PAYLOAD_SHORT]. */
  internal const val PAYLOAD_SHORT_MAX = 0xffffL
  /**
   * Value for [B1_MASK_LENGTH] which indicates the next eight bytes are the unsigned
   * length.
   */
  internal const val PAYLOAD_LONG = 127

  /** Used when an unchecked exception was thrown in a listener. */
  internal const val CLOSE_CLIENT_GOING_AWAY = 1001
  /** Used when an empty close frame was received (i.e., without a status code). */
  internal const val CLOSE_NO_STATUS_CODE = 1005

  fun toggleMask(cursor: Buffer.UnsafeCursor, key: ByteArray) {
    var keyIndex = 0
    val keyLength = key.size
    do {
      val buffer = cursor.data
      var i = cursor.start
      val end = cursor.end
      if (buffer != null) {
        while (i < end) {
          keyIndex %= keyLength // Reassign to prevent overflow breaking counter.

          // Byte xor is experimental in Kotlin so we coerce bytes to int, xor them
          // and convert back to byte.
          val bufferInt: Int = buffer[i].toInt()
          val keyInt: Int = key[keyIndex].toInt()
          buffer[i] = (bufferInt xor keyInt).toByte()

          i++
          keyIndex++
        }
      }
    } while (cursor.next() != -1)
  }

  fun closeCodeExceptionMessage(code: Int): String? {
    return if (code < 1000 || code >= 5000) {
      "Code must be in range [1000,5000): $code"
    } else if (code in 1004..1006 || code in 1015..2999) {
      "Code $code is reserved and may not be used."
    } else {
      null
    }
  }

  fun validateCloseCode(code: Int) {
    val message = closeCodeExceptionMessage(code)
    require(message == null) { message!! }
  }

  fun acceptHeader(key: String): String {
    return (key + ACCEPT_MAGIC).encodeUtf8().sha1().base64()
  }
}
