/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.http2

import okhttp3.internal.format
import okio.ByteString.Companion.encodeUtf8

object Http2 {
  @JvmField
  val CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeUtf8()

  /** The initial max frame size, applied independently writing to, or reading from the peer. */
  const val INITIAL_MAX_FRAME_SIZE = 0x4000 // 16384

  const val TYPE_DATA = 0x0
  const val TYPE_HEADERS = 0x1
  const val TYPE_PRIORITY = 0x2
  const val TYPE_RST_STREAM = 0x3
  const val TYPE_SETTINGS = 0x4
  const val TYPE_PUSH_PROMISE = 0x5
  const val TYPE_PING = 0x6
  const val TYPE_GOAWAY = 0x7
  const val TYPE_WINDOW_UPDATE = 0x8
  const val TYPE_CONTINUATION = 0x9

  const val FLAG_NONE = 0x0
  const val FLAG_ACK = 0x1 // Used for settings and ping.
  const val FLAG_END_STREAM = 0x1 // Used for headers and data.
  const val FLAG_END_HEADERS = 0x4 // Used for headers and continuation.
  const val FLAG_END_PUSH_PROMISE = 0x4
  const val FLAG_PADDED = 0x8 // Used for headers and data.
  const val FLAG_PRIORITY = 0x20 // Used for headers.
  const val FLAG_COMPRESSED = 0x20 // Used for data.

  /** Lookup table for valid frame types. */
  private val FRAME_NAMES = arrayOf(
      "DATA", "HEADERS", "PRIORITY", "RST_STREAM", "SETTINGS", "PUSH_PROMISE", "PING", "GOAWAY",
      "WINDOW_UPDATE", "CONTINUATION"
  )

  /**
   * Lookup table for valid flags for DATA, HEADERS, CONTINUATION. Invalid combinations are
   * represented in binary.
   */
  private val FLAGS = arrayOfNulls<String>(0x40) // Highest bit flag is 0x20.
  private val BINARY = Array(256) {
    format("%8s", Integer.toBinaryString(it)).replace(' ', '0')
  }

  init {
    FLAGS[FLAG_NONE] = ""
    FLAGS[FLAG_END_STREAM] = "END_STREAM"

    val prefixFlags = intArrayOf(FLAG_END_STREAM)

    FLAGS[FLAG_PADDED] = "PADDED"
    for (prefixFlag in prefixFlags) {
      FLAGS[prefixFlag or FLAG_PADDED] = FLAGS[prefixFlag] + "|PADDED"
    }

    FLAGS[FLAG_END_HEADERS] = "END_HEADERS" // Same as END_PUSH_PROMISE.
    FLAGS[FLAG_PRIORITY] = "PRIORITY" // Same as FLAG_COMPRESSED.
    FLAGS[FLAG_END_HEADERS or FLAG_PRIORITY] = "END_HEADERS|PRIORITY" // Only valid on HEADERS.
    val frameFlags = intArrayOf(FLAG_END_HEADERS, FLAG_PRIORITY, FLAG_END_HEADERS or FLAG_PRIORITY)

    for (frameFlag in frameFlags) {
      for (prefixFlag in prefixFlags) {
        FLAGS[prefixFlag or frameFlag] = FLAGS[prefixFlag] + '|'.toString() + FLAGS[frameFlag]
        FLAGS[prefixFlag or frameFlag or FLAG_PADDED] =
            FLAGS[prefixFlag] + '|'.toString() + FLAGS[frameFlag] + "|PADDED"
      }
    }

    for (i in FLAGS.indices) { // Fill in holes with binary representation.
      if (FLAGS[i] == null) FLAGS[i] = BINARY[i]
    }
  }

  /**
   * Returns human-readable representation of HTTP/2 frame headers.
   *
   * The format is:
   *
   * ```
   * direction streamID length type flags
   * ```
   *
   * Where direction is `<<` for inbound and `>>` for outbound.
   *
   * For example, the following would indicate a HEAD request sent from the client.
   * ```
   * `<< 0x0000000f    12 HEADERS       END_HEADERS|END_STREAM
   * ```
   */
  fun frameLog(
    inbound: Boolean,
    streamId: Int,
    length: Int,
    type: Int,
    flags: Int
  ): String {
    val formattedType = if (type < FRAME_NAMES.size) FRAME_NAMES[type] else format("0x%02x", type)
    val formattedFlags = formatFlags(type, flags)
    val direction = if (inbound) "<<" else ">>"
    return format("%s 0x%08x %5d %-13s %s",
        direction, streamId, length, formattedType, formattedFlags)
  }

  /**
   * Looks up valid string representing flags from the table. Invalid combinations are represented
   * in binary.
   */
  // Visible for testing.
  fun formatFlags(type: Int, flags: Int): String {
    if (flags == 0) return ""
    when (type) {
      // Special case types that have 0 or 1 flag.
      TYPE_SETTINGS, TYPE_PING -> return if (flags == FLAG_ACK) "ACK" else BINARY[flags]
      TYPE_PRIORITY, TYPE_RST_STREAM, TYPE_GOAWAY, TYPE_WINDOW_UPDATE -> return BINARY[flags]
    }
    val result = if (flags < FLAGS.size) FLAGS[flags]!! else BINARY[flags]
    // Special case types that have overlap flag values.
    return when {
      type == TYPE_PUSH_PROMISE && flags and FLAG_END_PUSH_PROMISE != 0 -> {
        result.replace("HEADERS", "PUSH_PROMISE") // TODO: Avoid allocation.
      }
      type == TYPE_DATA && flags and FLAG_COMPRESSED != 0 -> {
        result.replace("PRIORITY", "COMPRESSED") // TODO: Avoid allocation.
      }
      else -> result
    }
  }
}
