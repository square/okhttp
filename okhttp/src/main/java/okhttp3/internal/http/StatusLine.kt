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
package okhttp3.internal.http

import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException

/** An HTTP response status line like "HTTP/1.1 200 OK". */
class StatusLine(
  @JvmField val protocol: Protocol,
  @JvmField val code: Int,
  @JvmField val message: String
) {

  override fun toString(): String {
    return buildString {
      if (protocol == Protocol.HTTP_1_0) {
        append("HTTP/1.0")
      } else {
        append("HTTP/1.1")
      }
      append(' ').append(code)
      append(' ').append(message)
    }
  }

  companion object {
    /** Numeric status code, 307: Temporary Redirect. */
    const val HTTP_TEMP_REDIRECT = 307
    const val HTTP_PERM_REDIRECT = 308
    const val HTTP_CONTINUE = 100

    fun get(response: Response): StatusLine {
      return StatusLine(response.protocol, response.code, response.message)
    }

    @Throws(IOException::class)
    fun parse(statusLine: String): StatusLine {
      // H T T P / 1 . 1   2 0 0   T e m p o r a r y   R e d i r e c t
      // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0

      // Parse protocol like "HTTP/1.1" followed by a space.
      val codeStart: Int
      val protocol: Protocol
      if (statusLine.startsWith("HTTP/1.")) {
        if (statusLine.length < 9 || statusLine[8] != ' ') {
          throw ProtocolException("Unexpected status line: $statusLine")
        }
        val httpMinorVersion = statusLine[7] - '0'
        codeStart = 9
        protocol = if (httpMinorVersion == 0) {
          Protocol.HTTP_1_0
        } else if (httpMinorVersion == 1) {
          Protocol.HTTP_1_1
        } else {
          throw ProtocolException("Unexpected status line: $statusLine")
        }
      } else if (statusLine.startsWith("ICY ")) {
        // Shoutcast uses ICY instead of "HTTP/1.0".
        protocol = Protocol.HTTP_1_0
        codeStart = 4
      } else {
        throw ProtocolException("Unexpected status line: $statusLine")
      }

      // Parse response code like "200". Always 3 digits.
      if (statusLine.length < codeStart + 3) {
        throw ProtocolException("Unexpected status line: $statusLine")
      }
      val code = try {
        Integer.parseInt(statusLine.substring(codeStart, codeStart + 3))
      } catch (_: NumberFormatException) {
        throw ProtocolException("Unexpected status line: $statusLine")
      }

      // Parse an optional response message like "OK" or "Not Modified". If it
      // exists, it is separated from the response code by a space.
      var message = ""
      if (statusLine.length > codeStart + 3) {
        if (statusLine[codeStart + 3] != ' ') {
          throw ProtocolException("Unexpected status line: $statusLine")
        }
        message = statusLine.substring(codeStart + 4)
      }

      return StatusLine(protocol, code, message)
    }
  }
}