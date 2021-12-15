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

import java.io.Closeable
import java.io.IOException
import java.util.Random
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_FIN
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV1
import okhttp3.internal.ws.WebSocketProtocol.B1_FLAG_MASK
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_CLOSE
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PING
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PONG
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_LONG
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT_MAX
import okhttp3.internal.ws.WebSocketProtocol.toggleMask
import okhttp3.internal.ws.WebSocketProtocol.validateCloseCode
import okio.Buffer
import okio.BufferedSink
import okio.ByteString

/**
 * An [RFC 6455][rfc_6455]-compatible WebSocket frame writer.
 *
 * This class is not thread safe.
 *
 * [rfc_6455]: http://tools.ietf.org/html/rfc6455
 */
class WebSocketWriter(
  private val isClient: Boolean,
  val sink: BufferedSink,
  val random: Random,
  private val perMessageDeflate: Boolean,
  private val noContextTakeover: Boolean,
  private val minimumDeflateSize: Long
) : Closeable {
  /** This holds outbound data for compression and masking. */
  private val messageBuffer = Buffer()

  /** The [Buffer] of [sink]. Write to this and then flush/emit [sink]. */
  private val sinkBuffer: Buffer = sink.buffer
  private var writerClosed = false

  /** Lazily initialized on first use. */
  private var messageDeflater: MessageDeflater? = null

  // Masks are only a concern for client writers.
  private val maskKey: ByteArray? = if (isClient) ByteArray(4) else null
  private val maskCursor: Buffer.UnsafeCursor? = if (isClient) Buffer.UnsafeCursor() else null

  /** Send a ping with the supplied [payload]. */
  @Throws(IOException::class)
  fun writePing(payload: ByteString) {
    writeControlFrame(OPCODE_CONTROL_PING, payload)
  }

  /** Send a pong with the supplied [payload]. */
  @Throws(IOException::class)
  fun writePong(payload: ByteString) {
    writeControlFrame(OPCODE_CONTROL_PONG, payload)
  }

  /**
   * Send a close frame with optional code and reason.
   *
   * @param code Status code as defined by
   *     [Section 7.4 of RFC 6455](http://tools.ietf.org/html/rfc6455#section-7.4) or `0`.
   * @param reason Reason for shutting down or `null`.
   */
  @Throws(IOException::class)
  fun writeClose(code: Int, reason: ByteString?) {
    var payload = ByteString.EMPTY
    if (code != 0 || reason != null) {
      if (code != 0) {
        validateCloseCode(code)
      }
      payload = Buffer().run {
        writeShort(code)
        if (reason != null) {
          write(reason)
        }
        readByteString()
      }
    }

    try {
      writeControlFrame(OPCODE_CONTROL_CLOSE, payload)
    } finally {
      writerClosed = true
    }
  }

  @Throws(IOException::class)
  private fun writeControlFrame(opcode: Int, payload: ByteString) {
    if (writerClosed) throw IOException("closed")

    val length = payload.size
    require(length <= PAYLOAD_BYTE_MAX) {
      "Payload size must be less than or equal to $PAYLOAD_BYTE_MAX"
    }

    val b0 = B0_FLAG_FIN or opcode
    sinkBuffer.writeByte(b0)

    var b1 = length
    if (isClient) {
      b1 = b1 or B1_FLAG_MASK
      sinkBuffer.writeByte(b1)

      random.nextBytes(maskKey!!)
      sinkBuffer.write(maskKey)

      if (length > 0) {
        val payloadStart = sinkBuffer.size
        sinkBuffer.write(payload)

        sinkBuffer.readAndWriteUnsafe(maskCursor!!)
        maskCursor.seek(payloadStart)
        toggleMask(maskCursor, maskKey)
        maskCursor.close()
      }
    } else {
      sinkBuffer.writeByte(b1)
      sinkBuffer.write(payload)
    }

    sink.flush()
  }

  @Throws(IOException::class)
  fun writeMessageFrame(formatOpcode: Int, data: ByteString) {
    if (writerClosed) throw IOException("closed")

    messageBuffer.write(data)

    var b0 = formatOpcode or B0_FLAG_FIN
    if (perMessageDeflate && data.size >= minimumDeflateSize) {
      val messageDeflater = this.messageDeflater
          ?: MessageDeflater(noContextTakeover).also { this.messageDeflater = it }
      messageDeflater.deflate(messageBuffer)
      b0 = b0 or B0_FLAG_RSV1
    }
    val dataSize = messageBuffer.size
    sinkBuffer.writeByte(b0)

    var b1 = 0
    if (isClient) {
      b1 = b1 or B1_FLAG_MASK
    }
    when {
      dataSize <= PAYLOAD_BYTE_MAX -> {
        b1 = b1 or dataSize.toInt()
        sinkBuffer.writeByte(b1)
      }
      dataSize <= PAYLOAD_SHORT_MAX -> {
        b1 = b1 or PAYLOAD_SHORT
        sinkBuffer.writeByte(b1)
        sinkBuffer.writeShort(dataSize.toInt())
      }
      else -> {
        b1 = b1 or PAYLOAD_LONG
        sinkBuffer.writeByte(b1)
        sinkBuffer.writeLong(dataSize)
      }
    }

    if (isClient) {
      random.nextBytes(maskKey!!)
      sinkBuffer.write(maskKey)

      if (dataSize > 0L) {
        messageBuffer.readAndWriteUnsafe(maskCursor!!)
        maskCursor.seek(0L)
        toggleMask(maskCursor, maskKey)
        maskCursor.close()
      }
    }

    sinkBuffer.write(messageBuffer, dataSize)
    sink.emit()
  }

  override fun close() {
    messageDeflater?.close()
  }
}
