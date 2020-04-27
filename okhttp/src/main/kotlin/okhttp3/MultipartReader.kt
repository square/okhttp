/*
 * Copyright (C) 2020 Square, Inc.
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

import java.io.Closeable
import java.io.IOException
import java.net.ProtocolException
import okhttp3.internal.http1.HeadersReader
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Options
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * Reads a stream of [RFC 2046][rfc_2046] multipart body parts. Callers read parts one-at-a-time
 * until [nextPart] returns null. After calling [nextPart] any preceding parts should not be read.
 *
 * Typical use loops over the parts in sequence:
 *
 * ```
 * val response: Response = call.execute()
 * val multipartReader = MultipartReader(response.body!!)
 *
 * multipartReader.use {
 *   while (true) {
 *     val part = multipartReader.nextPart() ?: break
 *     process(part.headers, part.body)
 *   }
 * }
 * ```
 *
 * Note that [nextPart] will skip any unprocessed data from the preceding part. If the preceding
 * part is particularly large or if the underlying source is particularly slow, the [nextPart] call
 * may be slow!
 *
 * Closing a part **does not** close this multipart reader; callers must explicitly close this with
 * [close].
 *
 * [rfc_2046]: http://www.ietf.org/rfc/rfc2046.txt
 */
class MultipartReader @Throws(IOException::class) constructor(
  private val source: BufferedSource,
  @get:JvmName("boundary") val boundary: String
) : Closeable {
  /** This delimiter typically precedes the first part. */
  private val dashDashBoundary = Buffer()
      .writeUtf8("--")
      .writeUtf8(boundary)
      .readByteString()

  /**
   * This delimiter typically precedes all subsequent parts. It may also precede the first part
   * if the body contains a preamble.
   */
  private val crlfDashDashBoundary = Buffer()
      .writeUtf8("\r\n--")
      .writeUtf8(boundary)
      .readByteString()

  private var partCount = 0
  private var closed = false
  private var noMoreParts = false

  /** This is only part that's allowed to read from the underlying source. */
  private var currentPart: PartSource? = null

  @Throws(IOException::class)
  constructor(response: ResponseBody) : this(
      source = response.source(),
      boundary = response.contentType()?.parameter("boundary")
          ?: throw ProtocolException("expected the Content-Type to have a boundary parameter")
  )

  @Throws(IOException::class)
  fun nextPart(): Part? {
    check(!closed) { "closed" }

    if (noMoreParts) return null

    // Read a boundary, skipping the remainder of the preceding part as necessary.
    if (partCount == 0 && source.rangeEquals(0L, dashDashBoundary)) {
      // This is the first part. Consume "--" followed by the boundary.
      source.skip(dashDashBoundary.size.toLong())
    } else {
      // This is a subsequent part or a preamble. Skip until "\r\n--" followed by the boundary.
      while (true) {
        val toSkip = currentPartBytesRemaining(maxResult = 8192)
        if (toSkip == 0L) break
        source.skip(toSkip)
      }
      source.skip(crlfDashDashBoundary.size.toLong())
    }

    // Read either \r\n or --\r\n to determine if there is another part.
    var whitespace = false
    afterBoundaryLoop@while (true) {
      when (source.select(afterBoundaryOptions)) {
        0 -> {
          // "\r\n": We've found a new part.
          partCount++
          break@afterBoundaryLoop
        }

        1 -> {
          // "--": No more parts.
          if (whitespace) throw ProtocolException("unexpected characters after boundary")
          if (partCount == 0) throw ProtocolException("expected at least 1 part")
          noMoreParts = true
          return null
        }

        2, 3 -> {
          // " " or "\t" Ignore whitespace and keep looking.
          whitespace = true
          continue@afterBoundaryLoop
        }

        -1 -> throw ProtocolException("unexpected characters after boundary")
      }
    }

    // There's another part. Parse its headers and return it.
    val headers = HeadersReader(source).readHeaders()
    val partSource = PartSource()
    currentPart = partSource
    return Part(headers, partSource.buffer())
  }

  /** A single part in the stream. It is an error to read this after calling [nextPart]. */
  private inner class PartSource : Source {
    private val timeout = Timeout()

    override fun close() {
      if (currentPart == this) {
        currentPart = null
      }
    }

    override fun read(sink: Buffer, byteCount: Long): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
      check(currentPart == this) { "closed" }

      source.timeout().intersectWith(timeout) {
        return when (val limit = currentPartBytesRemaining(maxResult = byteCount)) {
          0L -> -1L // No more bytes in this part.
          else -> source.read(sink, limit)
        }
      }

      error("unreachable") // TODO(jwilson): fix intersectWith() to return T.
    }

    override fun timeout() = timeout
  }

  /**
   * Returns a value in [0..maxByteCount] with the number of bytes that can be read from [source] in
   * the current part. If this returns 0 the current part is exhausted; otherwise it has at least
   * one byte left to read.
   */
  private fun currentPartBytesRemaining(maxResult: Long): Long {
    source.require(crlfDashDashBoundary.size.toLong())

    return when (val delimiterIndex = source.buffer.indexOf(crlfDashDashBoundary)) {
      -1L -> minOf(maxResult, source.buffer.size - crlfDashDashBoundary.size + 1)
      else -> minOf(maxResult, delimiterIndex)
    }
  }

  @Throws(IOException::class)
  override fun close() {
    if (closed) return
    closed = true
    currentPart = null
    source.close()
  }

  /** A single part in a multipart body. */
  class Part(
    @get:JvmName("headers") val headers: Headers,
    @get:JvmName("body") val body: BufferedSource
  ) : Closeable by body

  internal companion object {
    /** These options follow the boundary. */
    val afterBoundaryOptions = Options.of(
        "\r\n".encodeUtf8(), // 0.  "\r\n"  More parts.
        "--".encodeUtf8(), //   1.  "--"    No more parts.
        " ".encodeUtf8(), //    2.  " "     Optional whitespace. Only used if there are more parts.
        "\t".encodeUtf8() //    3.  "\t"    Optional whitespace. Only used if there are more parts.
    )
  }
}
