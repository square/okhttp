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

import okhttp3.internal.and
import okhttp3.internal.toHexString
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_FIN
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV1
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV2
import okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV3
import okhttp3.internal.ws.WebSocketProtocol.B0_MASK_OPCODE
import okhttp3.internal.ws.WebSocketProtocol.B1_FLAG_MASK
import okhttp3.internal.ws.WebSocketProtocol.B1_MASK_LENGTH
import okhttp3.internal.ws.WebSocketProtocol.CLOSE_NO_STATUS_CODE
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTINUATION
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_CLOSE
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PING
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PONG
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_FLAG_CONTROL
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_LONG
import okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT
import okhttp3.internal.ws.WebSocketProtocol.toggleMask
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/**
 * An [RFC 6455][rfc_6455]-compatible WebSocket frame reader.
 *
 * This class is not thread safe.
 *
 * [rfc_6455]: http://tools.ietf.org/html/rfc6455
 */
internal class WebSocketReader(
  private val isClient: Boolean,
  val source: BufferedSource,
  private val frameCallback: FrameCallback
) {

  var closed = false

  // Stateful data about the current frame.
  private var opcode = 0
  private var frameLength = 0L
  private var isFinalFrame = false
  private var isControlFrame = false

  private val controlFrameBuffer = Buffer()
  private val messageFrameBuffer = Buffer()

  // Masks are only a concern for server writers.
  private val maskKey: ByteArray? = if (isClient) null else ByteArray(4)
  private val maskCursor: Buffer.UnsafeCursor? = if (isClient) null else Buffer.UnsafeCursor()

  interface FrameCallback {
    @Throws(IOException::class)
    fun onReadMessage(text: String)

    @Throws(IOException::class)
    fun onReadMessage(bytes: ByteString)

    fun onReadPing(payload: ByteString)
    fun onReadPong(payload: ByteString)
    fun onReadClose(code: Int, reason: String)
  }

  /**
   * Process the next protocol frame.
   *
   *  * If it is a control frame this will result in a single call to [FrameCallback].
   *  * If it is a message frame this will result in a single call to [FrameCallback.onReadMessage].
   *    If the message spans multiple frames, each interleaved control frame will result in a
   *    corresponding call to [FrameCallback].
   */
  @Throws(IOException::class)
  fun processNextFrame() {
    readHeader()
    if (isControlFrame) {
      readControlFrame()
    } else {
      readMessageFrame()
    }
  }

  @Throws(IOException::class, ProtocolException::class)
  private fun readHeader() {
    if (closed) throw IOException("closed")

    // Disable the timeout to read the first byte of a new frame.
    val b0: Int
    val timeoutBefore = source.timeout().timeoutNanos()
    source.timeout().clearTimeout()
    try {
      b0 = source.readByte() and 0xff
    } finally {
      source.timeout().timeout(timeoutBefore, TimeUnit.NANOSECONDS)
    }

    opcode = b0 and B0_MASK_OPCODE
    isFinalFrame = b0 and B0_FLAG_FIN != 0
    isControlFrame = b0 and OPCODE_FLAG_CONTROL != 0

    // Control frames must be final frames (cannot contain continuations).
    if (isControlFrame && !isFinalFrame) {
      throw ProtocolException("Control frames must be final.")
    }

    val reservedFlag1 = b0 and B0_FLAG_RSV1 != 0
    val reservedFlag2 = b0 and B0_FLAG_RSV2 != 0
    val reservedFlag3 = b0 and B0_FLAG_RSV3 != 0
    if (reservedFlag1 || reservedFlag2 || reservedFlag3) {
      // Reserved flags are for extensions which we currently do not support.
      throw ProtocolException("Reserved flags are unsupported.")
    }

    val b1 = source.readByte() and 0xff

    val isMasked = b1 and B1_FLAG_MASK != 0
    if (isMasked == isClient) {
      // Masked payloads must be read on the server. Unmasked payloads must be read on the client.
      throw ProtocolException(if (isClient) {
        "Server-sent frames must not be masked."
      } else {
        "Client-sent frames must be masked."
      })
    }

    // Get frame length, optionally reading from follow-up bytes if indicated by special values.
    frameLength = (b1 and B1_MASK_LENGTH).toLong()
    if (frameLength == PAYLOAD_SHORT.toLong()) {
      frameLength = (source.readShort() and 0xffff).toLong() // Value is unsigned.
    } else if (frameLength == PAYLOAD_LONG.toLong()) {
      frameLength = source.readLong()
      if (frameLength < 0L) {
        throw ProtocolException(
            "Frame length 0x${frameLength.toHexString()} > 0x7FFFFFFFFFFFFFFF")
      }
    }

    if (isControlFrame && frameLength > PAYLOAD_BYTE_MAX) {
      throw ProtocolException("Control frame must be less than ${PAYLOAD_BYTE_MAX}B.")
    }

    if (isMasked) {
      // Read the masking key as bytes so that they can be used directly for unmasking.
      source.readFully(maskKey!!)
    }
  }

  @Throws(IOException::class)
  private fun readControlFrame() {
    if (frameLength > 0L) {
      source.readFully(controlFrameBuffer, frameLength)

      if (!isClient) {
        controlFrameBuffer.readAndWriteUnsafe(maskCursor!!)
        maskCursor.seek(0)
        toggleMask(maskCursor, maskKey!!)
        maskCursor.close()
      }
    }

    when (opcode) {
      OPCODE_CONTROL_PING -> {
        frameCallback.onReadPing(controlFrameBuffer.readByteString())
      }
      OPCODE_CONTROL_PONG -> {
        frameCallback.onReadPong(controlFrameBuffer.readByteString())
      }
      OPCODE_CONTROL_CLOSE -> {
        var code = CLOSE_NO_STATUS_CODE
        var reason = ""
        val bufferSize = controlFrameBuffer.size
        if (bufferSize == 1L) {
          throw ProtocolException("Malformed close payload length of 1.")
        } else if (bufferSize != 0L) {
          code = controlFrameBuffer.readShort().toInt()
          reason = controlFrameBuffer.readUtf8()
          val codeExceptionMessage = WebSocketProtocol.closeCodeExceptionMessage(code)
          if (codeExceptionMessage != null) throw ProtocolException(codeExceptionMessage)
        }
        frameCallback.onReadClose(code, reason)
        closed = true
      }
      else -> {
        throw ProtocolException("Unknown control opcode: " + opcode.toHexString())
      }
    }
  }

  @Throws(IOException::class)
  private fun readMessageFrame() {
    val opcode = this.opcode
    if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
      throw ProtocolException("Unknown opcode: ${opcode.toHexString()}")
    }

    readMessage()

    if (opcode == OPCODE_TEXT) {
      frameCallback.onReadMessage(messageFrameBuffer.readUtf8())
    } else {
      frameCallback.onReadMessage(messageFrameBuffer.readByteString())
    }
  }

  /** Read headers and process any control frames until we reach a non-control frame. */
  @Throws(IOException::class)
  private fun readUntilNonControlFrame() {
    while (!closed) {
      readHeader()
      if (!isControlFrame) {
        break
      }
      readControlFrame()
    }
  }

  /**
   * Reads a message body into across one or more frames. Control frames that occur between
   * fragments will be processed. If the message payload is masked this will unmask as it's being
   * processed.
   */
  @Throws(IOException::class)
  private fun readMessage() {
    while (true) {
      if (closed) throw IOException("closed")

      if (frameLength > 0L) {
        source.readFully(messageFrameBuffer, frameLength)

        if (!isClient) {
          messageFrameBuffer.readAndWriteUnsafe(maskCursor!!)
          maskCursor.seek(messageFrameBuffer.size - frameLength)
          toggleMask(maskCursor, maskKey!!)
          maskCursor.close()
        }
      }

      if (isFinalFrame) break // We are exhausted and have no continuations.

      readUntilNonControlFrame()
      if (opcode != OPCODE_CONTINUATION) {
        throw ProtocolException("Expected continuation opcode. Got: ${opcode.toHexString()}")
      }
    }
  }
}
