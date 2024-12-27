/*
 * Copyright (C) 2012 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.Headers.Companion.headersOf
import okhttp3.TestUtil.headerEntries
import okhttp3.internal.http2.Http2ExchangeCodec.Companion.http2HeadersList
import okhttp3.internal.http2.Http2ExchangeCodec.Companion.readHttp2HeadersList
import org.junit.jupiter.api.Test

class HeadersRequestTest {
  @Test fun readNameValueBlockDropsForbiddenHeadersHttp2() {
    val headerBlock =
      headersOf(
        ":status",
        "200 OK",
        ":version",
        "HTTP/1.1",
        "connection",
        "close",
      )
    val request = Request.Builder().url("http://square.com/").build()
    val response = readHttp2HeadersList(headerBlock, Protocol.HTTP_2).request(request).build()
    val headers = response.headers
    assertThat(headers.size).isEqualTo(1)
    assertThat(headers.name(0)).isEqualTo(":version")
    assertThat(headers.value(0)).isEqualTo("HTTP/1.1")
  }

  @Test fun http2HeadersListDropsForbiddenHeadersHttp2() {
    val request =
      Request.Builder()
        .url("http://square.com/")
        .header("Connection", "upgrade")
        .header("Upgrade", "websocket")
        .header("Host", "square.com")
        .header("TE", "gzip")
        .build()
    val expected =
      headerEntries(
        ":method",
        "GET",
        ":path",
        "/",
        ":authority",
        "square.com",
        ":scheme",
        "http",
      )
    assertThat(http2HeadersList(request)).isEqualTo(expected)
  }

  @Test fun http2HeadersListDontDropTeIfTrailersHttp2() {
    val request =
      Request.Builder()
        .url("http://square.com/")
        .header("TE", "trailers")
        .build()
    val expected =
      headerEntries(
        ":method",
        "GET",
        ":path",
        "/",
        ":scheme",
        "http",
        "te",
        "trailers",
      )
    assertThat(http2HeadersList(request)).isEqualTo(expected)
  }
}
