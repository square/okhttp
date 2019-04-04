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

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.ProtocolException

@RunWith(Parameterized::class)
internal class WebSocketOptionsTest(
  private val extension: String,
  private val expected: WebSocketOptions?,
  private val expectedExceptionMessage: String?
) {

  private companion object {

    val NO_COMPRESSION = WebSocketOptions(
        compressionEnabled = false,
        contextTakeover = false
    )

    val COMPRESSION_NO_TAKEOVER = WebSocketOptions(
        compressionEnabled = true,
        contextTakeover = false
    )

    val COMPRESSION_WITH_TAKEOVER = WebSocketOptions(
        compressionEnabled = true,
        contextTakeover = true
    )

    /**
     * The only possible extension values in request are
     *
     * `permessage-deflate; server_no_context_takeover; client_no_context_takeover`
     *
     * and
     *
     * `permessage-deflate; server_max_window_bits=15; client_max_window_bits=15`
     *
     * See [rfc7692#section-7.1](https://tools.ietf.org/html/rfc7692#section-7.1) for details on
     * negotiation process.
     */
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun parameters(): Collection<Array<out Any?>> = listOf(
        arrayOf(
            "",
            NO_COMPRESSION,
            null
        ),

        arrayOf(
            "permessage-deflate",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate;",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate, " +
            "permessage-deflate; server_no_context_takeover; client_no_context_takeover",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),

        arrayOf(
            "permessage-deflate; server_no_context_takeover; client_no_context_takeover",
            COMPRESSION_NO_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; client_no_context_takeover; server_no_context_takeover",
            COMPRESSION_NO_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; client_no_context_takeover",
            COMPRESSION_NO_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; server_no_context_takeover",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),

        arrayOf(
            "permessage-deflate; server_max_window_bits=15; client_max_window_bits=15",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=15; server_max_window_bits=15",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; server_max_window_bits=8; client_max_window_bits=15",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=15",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),
        arrayOf(
            "permessage-deflate; server_max_window_bits=15",
            COMPRESSION_WITH_TAKEOVER,
            null
        ),

        arrayOf(
            "permessage-deflate; unknown",
            null,
            "Unknown option: 'unknown'"
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=7",
            null,
            "Invalid option value. " +
            "15 is the only supported value for 'client_max_window_bits'. Actual: 7"
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=16",
            null,
            "Invalid option value. " +
            "15 is the only supported value for 'client_max_window_bits'. Actual: 16"
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=14",
            null,
            "Invalid option value. " +
            "15 is the only supported value for 'client_max_window_bits'. Actual: 14"
        ),
        arrayOf(
            "permessage-deflate; server_max_window_bits=7",
            null,
            "Invalid option value. 'server_max_window_bits' must be in [8,15]. Actual: 7"
        ),
        arrayOf(
            "permessage-deflate; server_max_window_bits=16",
            null,
            "Invalid option value. 'server_max_window_bits' must be in [8,15]. Actual: 16"
        ),
        arrayOf(
            "permessage-deflate; client_max_window_bits=15; client_max_window_bits=15",
            null,
            "Duplicate options found in " +
            "'permessage-deflate; client_max_window_bits=15; client_max_window_bits=15'"
        ),

        arrayOf(
            ", permessage-deflate",
            null,
            "Sec-WebSocket-Extensions malformed: ', permessage-deflate'"
        ),

        arrayOf(
            "unknown-ext",
            null,
            "Extension not supported: 'unknown-ext'"
        )
    )

    private fun parse(extension: String) =
        WebSocketOptions.parseServerResponse(responseWithExtension(extension))

    private fun responseWithExtension(extension: String) =
        Response.Builder(responseWithoutExtension())
            .header("Sec-WebSocket-Extensions", extension)
            .build()
  }

  @Test fun parsed() {
    when {
      expected != null -> assertThat(parse(extension)).isEqualTo(expected)
      expectedExceptionMessage != null -> try {
        parse(extension)
        fail()
      } catch (e: ProtocolException) {
        assertThat(e.message).isEqualTo(expectedExceptionMessage)
      }
      else -> fail("expected value and expected exception are both null")
    }
  }
}

internal class WebSocketOptionsNoHeaderTest {

  private companion object {
    val NO_COMPRESSION = WebSocketOptions(
        compressionEnabled = false,
        contextTakeover = false
    )
  }

  @Test fun `missing extension header results in no compression`() {
    assertThat(WebSocketOptions.parseServerResponse(responseWithoutExtension()))
        .isEqualTo(NO_COMPRESSION)
  }
}

private fun responseWithoutExtension() = Response.Builder()
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .request(
        Request.Builder()
            .url("https://example.com/")
            .build()
    )
    .build()
