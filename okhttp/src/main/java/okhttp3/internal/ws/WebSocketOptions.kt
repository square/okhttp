/*
 * Copyright (C) 2019 Square, Inc.
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

import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException

data class WebSocketOptions(
  @JvmField val compressionEnabled: Boolean,
  @JvmField val contextTakeover: Boolean
) {
  companion object {
    private const val HEADER_WEB_SOCKET_EXTENSION = "Sec-WebSocket-Extensions"
    private const val EXTENSION_PERMESSAGE_DEFLATE = "permessage-deflate"

    private val NO_COMPRESSION = WebSocketOptions(
      compressionEnabled = false,
      contextTakeover = false
    )

    private val COMPRESSION_NO_TAKEOVER = WebSocketOptions(
      compressionEnabled = true,
      contextTakeover = false
    )

    private val COMPRESSION_WITH_TAKEOVER = WebSocketOptions(
      compressionEnabled = true,
      contextTakeover = true
    )

    @Throws(IOException::class)
    @JvmStatic
    fun parseServerResponse(response: Response): WebSocketOptions {
      // No extension header - server does not support permessage-deflate.
      val header = response.header(HEADER_WEB_SOCKET_EXTENSION) ?: return NO_COMPRESSION

      // Server is free to return empty header to indicate no compression
      // See end of https://tools.ietf.org/html/rfc7692#section-5 chapter
      if (header.isEmpty()) {
        return NO_COMPRESSION
      }

      val extension = header
        .split(", ")
        // Additional or fallback extensions are not supported currently.
        // See https://tools.ietf.org/html/rfc7692#section-5.2 for details.
        .getOrNull(0)

      if (extension.isNullOrBlank()) {
        throw ProtocolException("$HEADER_WEB_SOCKET_EXTENSION malformed: '$header'")
      }

      val tokens = extension.split("; ").map { it.replace(";", "") }
      if (tokens.isEmpty()) {
        throw ProtocolException("Extension could not be parsed: '$extension'")
      }

      val extensionName = tokens[0]
      if (extensionName != EXTENSION_PERMESSAGE_DEFLATE) {
        // Client MUST fail for extension it did not ask for
        throw ProtocolException("Extension not supported: '$extensionName'")
      }

      val options = tokens.drop(1).map(Option.Companion::parse)
      if (options.isEmpty()) {
        // Server did not force client_no_context_takeover,
        // so client can use compression with context takeover.
        return COMPRESSION_WITH_TAKEOVER
      }

      if (options.toSet().size != options.size) {
        // Client MUST fail for duplicate options.
        throw ProtocolException("Duplicate options found in '$extension'")
      }

      val clientNoContextTakeover = Option.CLIENT_NO_CONTEXT_TAKEOVER in options

      return if (clientNoContextTakeover) {
        COMPRESSION_NO_TAKEOVER
      } else {
        COMPRESSION_WITH_TAKEOVER
      }
    }
  }
}

private enum class Option(val id: String) {
  CLIENT_NO_CONTEXT_TAKEOVER("client_no_context_takeover"),
  SERVER_NO_CONTEXT_TAKEOVER("server_no_context_takeover"),
  CLIENT_MAX_WINDOW_BITS("client_max_window_bits"),
  SERVER_MAX_WINDOW_BITS("server_max_window_bits");

  companion object {
    /**
     * If server decides to use MAX_WINDOW_BITS less than 15, is fine.
     * The deflater has enough information from the deflated data
     * to parse it correctly.
     */
    private const val MIN_MAX_WINDOW_BITS = 8
    private const val MAX_MAX_WINDOW_BITS = 15

    private const val SUPPORTED_CLIENT_MAX_WINDOW_BITS = MAX_MAX_WINDOW_BITS

    fun parse(option: String): Option =
      when {
        option == CLIENT_NO_CONTEXT_TAKEOVER.id -> CLIENT_NO_CONTEXT_TAKEOVER
        option == SERVER_NO_CONTEXT_TAKEOVER.id -> SERVER_NO_CONTEXT_TAKEOVER
        option.startsWith(CLIENT_MAX_WINDOW_BITS.id) -> {
          verifyMaxWindowBits(CLIENT_MAX_WINDOW_BITS, option)
          CLIENT_MAX_WINDOW_BITS
        }
        option.startsWith(SERVER_MAX_WINDOW_BITS.id) -> {
          verifyMaxWindowBits(SERVER_MAX_WINDOW_BITS, option)
          SERVER_MAX_WINDOW_BITS
        }
        else -> throw ProtocolException("Unknown option: '$option'")
      }

    private fun verifyMaxWindowBits(option: Option, optionString: String) {
      val maxWindowBits = optionString.substring(option.id.length + 1)
                            // We should support quoted parameters, eg "15"
                            .replace("\"", "")
                            .toIntOrNull()
                          ?: throw ProtocolException(
                            "Failed to parse max_window_bits value from '$optionString'")

      if (option == CLIENT_MAX_WINDOW_BITS && maxWindowBits != SUPPORTED_CLIENT_MAX_WINDOW_BITS) {
        throw ProtocolException(
          "Invalid option value. $SUPPORTED_CLIENT_MAX_WINDOW_BITS " +
          "is the only supported value for '${CLIENT_MAX_WINDOW_BITS.id}'. " +
          "Actual: $maxWindowBits")
      }

      if (option == SERVER_MAX_WINDOW_BITS &&
          maxWindowBits !in MIN_MAX_WINDOW_BITS..MAX_MAX_WINDOW_BITS) {
        throw ProtocolException(
          "Invalid option value. " +
          "'${SERVER_MAX_WINDOW_BITS.id}' must be in " +
          "[$MIN_MAX_WINDOW_BITS,$MAX_MAX_WINDOW_BITS]. " +
          "Actual: $maxWindowBits")
      }
    }
  }
}
