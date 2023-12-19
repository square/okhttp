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

import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebSocketExtensionsTest {
  @Test
  fun emptyHeader() {
    assertThat(parse("")).isEqualTo(WebSocketExtensions())
  }

  @Test
  fun noExtensionHeader() {
    assertThat(WebSocketExtensions.parse(headersOf()))
        .isEqualTo(WebSocketExtensions())
  }

  @Test
  fun emptyExtension() {
    assertThat(parse(", permessage-deflate"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true, unknownValues = true))
  }

  @Test
  fun unknownExtension() {
    assertThat(parse("unknown-ext"))
        .isEqualTo(WebSocketExtensions(unknownValues = true))
  }

  @Test
  fun perMessageDeflate() {
    assertThat(parse("permessage-deflate"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true))
  }

  @Test
  fun emptyParameters() {
    assertThat(parse("permessage-deflate;"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true))
  }

  @Test
  fun repeatedPerMessageDeflate() {
    assertThat(parse("permessage-deflate, permessage-deflate; server_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            serverNoContextTakeover = true,
            unknownValues = true
        ))
  }

  @Test
  fun multiplePerMessageDeflateHeaders() {
    val extensions = WebSocketExtensions.parse(headersOf(
        "Sec-WebSocket-Extensions", "",
        "Sec-WebSocket-Extensions", "permessage-deflate"
    ))
    assertThat(extensions)
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true
        ))
  }

  @Test
  fun noContextTakeoverServerAndClient() {
    assertThat(parse("permessage-deflate; server_no_context_takeover; client_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true,
            serverNoContextTakeover = true
        ))
  }

  @Test
  fun everything() {
    assertThat(parse("permessage-deflate; client_max_window_bits=15; client_no_context_takeover; " +
        "server_max_window_bits=8; server_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientMaxWindowBits = 15,
            clientNoContextTakeover = true,
            serverMaxWindowBits = 8,
            serverNoContextTakeover = true
        ))
  }

  @Test
  fun noWhitespace() {
    assertThat(parse("permessage-deflate;server_no_context_takeover;client_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true,
            serverNoContextTakeover = true
        ))
  }

  @Test
  fun excessWhitespace() {
    assertThat(parse(
        "  permessage-deflate\t ; \tserver_no_context_takeover\t ;  client_no_context_takeover  "
    )).isEqualTo(WebSocketExtensions(
        perMessageDeflate = true,
        clientNoContextTakeover = true,
        serverNoContextTakeover = true
    ))
  }

  @Test
  fun noContextTakeoverClientAndServer() {
    assertThat(parse("permessage-deflate; client_no_context_takeover; server_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true,
            serverNoContextTakeover = true
        ))
  }

  @Test
  fun noContextTakeoverClient() {
    assertThat(parse("permessage-deflate; client_no_context_takeover"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true
        ))
  }

  @Test
  fun noContextTakeoverServer() {
    assertThat(parse("permessage-deflate; server_no_context_takeover")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverNoContextTakeover = true))
  }

  @Test
  fun clientMaxWindowBits() {
    assertThat(parse("permessage-deflate; client_max_window_bits=8")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; client_max_window_bits=\"8\"")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; client_max_window_bits=15")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 15))
    assertThat(parse("permessage-deflate; client_max_window_bits=\"15\"")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 15))
    assertThat(parse("permessage-deflate; client_max_window_bits\t =\t 8\t ")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; client_max_window_bits\t =\t \"8\"\t ")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, clientMaxWindowBits = 8))
  }

  @Test
  fun serverMaxWindowBits() {
    assertThat(parse("permessage-deflate; server_max_window_bits=8")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; server_max_window_bits=\"8\"")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; server_max_window_bits=15")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 15))
    assertThat(parse("permessage-deflate; server_max_window_bits=\"15\"")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 15))
    assertThat(parse("permessage-deflate; server_max_window_bits\t =\t 8\t ")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 8))
    assertThat(parse("permessage-deflate; server_max_window_bits\t =\t \"8\"\t ")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, serverMaxWindowBits = 8))
  }

  @Test
  fun unknownParameters() {
    assertThat(parse("permessage-deflate; unknown"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true, unknownValues = true))
    assertThat(parse("permessage-deflate; unknown_parameter=15"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true, unknownValues = true))
    assertThat(parse("permessage-deflate; unknown_parameter=15; unknown_parameter=15"))
        .isEqualTo(WebSocketExtensions(perMessageDeflate = true, unknownValues = true))
  }

  @Test
  fun unexpectedValue() {
    assertThat(parse("permessage-deflate; client_no_context_takeover=true"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true,
            unknownValues = true
        ))
    assertThat(parse("permessage-deflate; server_max_window_bits=true"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            unknownValues = true
        ))
  }

  @Test
  fun absentValue() {
    assertThat(parse("permessage-deflate; server_max_window_bits")).isEqualTo(
        WebSocketExtensions(perMessageDeflate = true, unknownValues = true))
  }

  @Test
  fun uppercase() {
    assertThat(parse("PERMESSAGE-DEFLATE; SERVER_NO_CONTEXT_TAKEOVER; CLIENT_NO_CONTEXT_TAKEOVER"))
        .isEqualTo(WebSocketExtensions(
            perMessageDeflate = true,
            clientNoContextTakeover = true,
            serverNoContextTakeover = true
        ))
  }

  private fun parse(extension: String) =
    WebSocketExtensions.parse(headersOf("Sec-WebSocket-Extensions", extension))
}
