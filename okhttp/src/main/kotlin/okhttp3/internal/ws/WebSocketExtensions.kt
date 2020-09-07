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
package okhttp3.internal.ws

import java.io.IOException
import okhttp3.Headers
import okhttp3.internal.delimiterOffset
import okhttp3.internal.trimSubstring

/**
 * Models the contents of a `Sec-WebSocket-Extensions` response header. OkHttp honors one extension
 * `permessage-deflate` and four parameters, `client_max_window_bits`, `client_no_context_takeover`,
 * `server_max_window_bits`, and `server_no_context_takeover`.
 *
 * Typically this will look like one of the following:
 *
 * ```
 * Sec-WebSocket-Extensions: permessage-deflate
 * Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits="15"
 * Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits=15
 * Sec-WebSocket-Extensions: permessage-deflate; client_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits="15"
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits=15
 * Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover;
 *     client_no_context_takeover
 * Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits="15";
 *     client_max_window_bits="15"; server_no_context_takeover; client_no_context_takeover
 * ```
 *
 * If any other extension or parameter is specified, then [unknownValues] will be true. Such
 * responses should be refused as their web socket extensions will not be understood.
 *
 * Note that [java.util.zip.Deflater] is hardcoded to use 15 bits (32 KiB) for
 * `client_max_window_bits` and [java.util.zip.Inflater] is hardcoded to use 15 bits (32 KiB) for
 * `server_max_window_bits`. This harms our ability to support these parameters:
 *
 *  * If `client_max_window_bits` is less than 15, OkHttp must close the web socket with code 1010.
 *    Otherwise it would compress values in a way that servers could not decompress.
 *  * If `server_max_window_bits` is less than 15, OkHttp will waste memory on an oversized buffer.
 *
 * See [RFC 7692, 7.1][rfc_7692] for details on negotiation process.
 *
 * [rfc_7692]: https://tools.ietf.org/html/rfc7692#section-7.1
 */
data class WebSocketExtensions(
  /** True if the agreed upon extensions includes the permessage-deflate extension. */
  @JvmField val perMessageDeflate: Boolean = false,

  /** Should be a value in [8..15]. Only 15 is acceptable by OkHttp as Java APIs are limited. */
  @JvmField val clientMaxWindowBits: Int? = null,

  /** True if the agreed upon extension parameters includes "client_no_context_takeover". */
  @JvmField val clientNoContextTakeover: Boolean = false,

  /** Should be a value in [8..15]. Any value in that range is acceptable by OkHttp. */
  @JvmField val serverMaxWindowBits: Int? = null,

  /** True if the agreed upon extension parameters includes "server_no_context_takeover". */
  @JvmField val serverNoContextTakeover: Boolean = false,

  /**
   * True if the agreed upon extensions or parameters contained values unrecognized by OkHttp.
   * Typically this indicates that the client will need to close the web socket with code 1010.
   */
  @JvmField val unknownValues: Boolean = false
) {

  fun noContextTakeover(clientOriginated: Boolean): Boolean {
    return if (clientOriginated) {
      clientNoContextTakeover // Client is deflating.
    } else {
      serverNoContextTakeover // Server is deflating.
    }
  }

  companion object {
    private const val HEADER_WEB_SOCKET_EXTENSION = "Sec-WebSocket-Extensions"

    @Throws(IOException::class)
    fun parse(responseHeaders: Headers): WebSocketExtensions {
      // Note that this code does case-insensitive comparisons, even though the spec doesn't specify
      // whether extension tokens and parameters are case-insensitive or not.

      var compressionEnabled = false
      var clientMaxWindowBits: Int? = null
      var clientNoContextTakeover = false
      var serverMaxWindowBits: Int? = null
      var serverNoContextTakeover = false
      var unexpectedValues = false

      // Parse each header.
      for (i in 0 until responseHeaders.size) {
        if (!responseHeaders.name(i).equals(HEADER_WEB_SOCKET_EXTENSION, ignoreCase = true)) {
          continue // Not a header we're interested in.
        }
        val header = responseHeaders.value(i)

        // Parse each extension.
        var pos = 0
        while (pos < header.length) {
          val extensionEnd = header.delimiterOffset(',', pos)
          val extensionTokenEnd = header.delimiterOffset(';', pos, extensionEnd)
          val extensionToken = header.trimSubstring(pos, extensionTokenEnd)
          pos = extensionTokenEnd + 1

          when {
            extensionToken.equals("permessage-deflate", ignoreCase = true) -> {
              if (compressionEnabled) unexpectedValues = true // Repeated extension!
              compressionEnabled = true

              // Parse each permessage-deflate parameter.
              while (pos < extensionEnd) {
                val parameterEnd = header.delimiterOffset(';', pos, extensionEnd)
                val equals = header.delimiterOffset('=', pos, parameterEnd)
                val name = header.trimSubstring(pos, equals)
                val value = if (equals < parameterEnd) {
                  header.trimSubstring(equals + 1, parameterEnd).removeSurrounding("\"")
                } else {
                  null
                }
                pos = parameterEnd + 1
                when {
                  name.equals("client_max_window_bits", ignoreCase = true) -> {
                    if (clientMaxWindowBits != null) unexpectedValues = true // Repeated parameter!
                    clientMaxWindowBits = value?.toIntOrNull()
                    if (clientMaxWindowBits == null) unexpectedValues = true // Not an int!
                  }
                  name.equals("client_no_context_takeover", ignoreCase = true) -> {
                    if (clientNoContextTakeover) unexpectedValues = true // Repeated parameter!
                    if (value != null) unexpectedValues = true // Unexpected value!
                    clientNoContextTakeover = true
                  }
                  name.equals("server_max_window_bits", ignoreCase = true) -> {
                    if (serverMaxWindowBits != null) unexpectedValues = true // Repeated parameter!
                    serverMaxWindowBits = value?.toIntOrNull()
                    if (serverMaxWindowBits == null) unexpectedValues = true // Not an int!
                  }
                  name.equals("server_no_context_takeover", ignoreCase = true) -> {
                    if (serverNoContextTakeover) unexpectedValues = true // Repeated parameter!
                    if (value != null) unexpectedValues = true // Unexpected value!
                    serverNoContextTakeover = true
                  }
                  else -> {
                    unexpectedValues = true // Unexpected parameter.
                  }
                }
              }
            }

            else -> {
              unexpectedValues = true // Unexpected extension.
            }
          }
        }
      }

      return WebSocketExtensions(
          perMessageDeflate = compressionEnabled,
          clientMaxWindowBits = clientMaxWindowBits,
          clientNoContextTakeover = clientNoContextTakeover,
          serverMaxWindowBits = serverMaxWindowBits,
          serverNoContextTakeover = serverNoContextTakeover,
          unknownValues = unexpectedValues
      )
    }
  }
}
