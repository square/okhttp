/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.internal.sse

import okhttp3.internal.toLongOrDefault
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Options
import java.io.IOException

class ServerSentEventReader(
  private val source: BufferedSource,
  private val callback: Callback
) {
  private var lastId: String? = null

  interface Callback {
    fun onEvent(id: String?, type: String?, data: String)
    fun onRetryChange(timeMs: Long)
  }

  /**
   * Process the next event. This will result in a single call to [Callback.onEvent] *unless* the
   * data section was empty. Any number of calls to [Callback.onRetryChange] may occur while
   * processing an event.
   *
   * @return false when EOF is reached
   */
  @Throws(IOException::class)
  fun processNextEvent(): Boolean {
    var id = lastId
    var type: String? = null
    val data = Buffer()

    while (true) {
      when (source.select(options)) {
        in 0..2 -> {
          completeEvent(id, type, data)
          return true
        }

        in 3..4 -> {
          source.readData(data)
        }

        in 5..7 -> {
          data.writeByte('\n'.toInt()) // 'data' on a line of its own.
        }

        in 8..9 -> {
          id = source.readUtf8LineStrict().takeIf { it.isNotEmpty() }
        }

        in 10..12 -> {
          id = null // 'id' on a line of its own.
        }

        in 13..14 -> {
          type = source.readUtf8LineStrict().takeIf { it.isNotEmpty() }
        }

        in 15..17 -> {
          type = null // 'event' on a line of its own
        }

        in 18..19 -> {
          val retryMs = source.readRetryMs()
          if (retryMs != -1L) {
            callback.onRetryChange(retryMs)
          }
        }

        -1 -> {
          val lineEnd = source.indexOfElement(CRLF)
          if (lineEnd != -1L) {
            // Skip the line and newline
            source.skip(lineEnd)
            source.select(options)
          } else {
            return false // No more newlines.
          }
        }

        else -> throw AssertionError()
      }
    }
  }

  @Throws(IOException::class)
  private fun completeEvent(id: String?, type: String?, data: Buffer) {
    if (data.size != 0L) {
      lastId = id
      data.skip(1L) // Leading newline.
      callback.onEvent(id, type, data.readUtf8())
    }
  }

  companion object {
    val options = Options.of(
        /*  0 */ "\r\n".encodeUtf8(),
        /*  1 */ "\r".encodeUtf8(),
        /*  2 */ "\n".encodeUtf8(),

        /*  3 */ "data: ".encodeUtf8(),
        /*  4 */ "data:".encodeUtf8(),

        /*  5 */ "data\r\n".encodeUtf8(),
        /*  6 */ "data\r".encodeUtf8(),
        /*  7 */ "data\n".encodeUtf8(),

        /*  8 */ "id: ".encodeUtf8(),
        /*  9 */ "id:".encodeUtf8(),

        /* 10 */ "id\r\n".encodeUtf8(),
        /* 11 */ "id\r".encodeUtf8(),
        /* 12 */ "id\n".encodeUtf8(),

        /* 13 */ "event: ".encodeUtf8(),
        /* 14 */ "event:".encodeUtf8(),

        /* 15 */ "event\r\n".encodeUtf8(),
        /* 16 */ "event\r".encodeUtf8(),
        /* 17 */ "event\n".encodeUtf8(),

        /* 18 */ "retry: ".encodeUtf8(),
        /* 19 */ "retry:".encodeUtf8()
    )

    private val CRLF = "\r\n".encodeUtf8()

    @Throws(IOException::class)
    private fun BufferedSource.readData(data: Buffer) {
      data.writeByte('\n'.toInt())
      readFully(data, indexOfElement(CRLF))
      select(options) // Skip the newline bytes.
    }

    @Throws(IOException::class)
    private fun BufferedSource.readRetryMs(): Long {
      val retryString = readUtf8LineStrict()
      return retryString.toLongOrDefault(-1L)
    }
  }
}
