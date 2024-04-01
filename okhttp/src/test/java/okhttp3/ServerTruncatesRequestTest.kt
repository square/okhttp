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

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.fail
import javax.net.ssl.SSLSocket
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.DoNotReadRequestBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.internal.duplex.AsyncRequestBody
import okhttp3.internal.http2.ErrorCode
import okhttp3.testing.PlatformRule
import okio.BufferedSink
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension

@Timeout(30)
@Tag("Slowish")
class ServerTruncatesRequestTest {
  @RegisterExtension
  @JvmField
  val platform = PlatformRule()

  @RegisterExtension
  @JvmField
  var clientTestRule = OkHttpClientTestRule()

  private val listener = RecordingEventListener()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()

  private var client =
    clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    platform.assumeNotOpenJSSE()
    platform.assumeHttp2Support()
  }

  @Test
  fun serverTruncatesRequestOnLongPostHttp1() {
    serverTruncatesRequestOnLongPost(https = false)
  }

  @Test
  fun serverTruncatesRequestOnLongPostHttp2() {
    enableProtocol(Protocol.HTTP_2)
    serverTruncatesRequestOnLongPost(https = true)
  }

  private fun serverTruncatesRequestOnLongPost(https: Boolean) {
    server.enqueue(
      MockResponse(
        body = "abc",
        socketPolicy = DoNotReadRequestBody(ErrorCode.NO_ERROR.httpCode),
      ),
    )

    val call =
      client.newCall(
        Request(
          url = server.url("/"),
          body = SlowRequestBody,
        ),
      )

    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("abc")
    }

    val expectedEvents = mutableListOf<String>()
    // Start out with standard events...
    expectedEvents += "CallStart"
    expectedEvents += "ProxySelectStart"
    expectedEvents += "ProxySelectEnd"
    expectedEvents += "DnsStart"
    expectedEvents += "DnsEnd"
    expectedEvents += "ConnectStart"
    if (https) {
      expectedEvents += "SecureConnectStart"
      expectedEvents += "SecureConnectEnd"
    }
    expectedEvents += "ConnectEnd"
    expectedEvents += "ConnectionAcquired"
    expectedEvents += "RequestHeadersStart"
    expectedEvents += "RequestHeadersEnd"
    expectedEvents += "RequestBodyStart"
    // ... but we can read the response even after writing the request fails.
    expectedEvents += "RequestFailed"
    expectedEvents += "ResponseHeadersStart"
    expectedEvents += "ResponseHeadersEnd"
    expectedEvents += "ResponseBodyStart"
    expectedEvents += "ResponseBodyEnd"
    expectedEvents += "ConnectionReleased"
    expectedEvents += "CallEnd"
    assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents)

    // Confirm that the connection pool was not corrupted by making another call.
    makeSimpleCall()
  }

  /**
   * If the server returns a full response, it doesn't really matter if the HTTP/2 stream is reset.
   * Attempts to write the request body fails fast.
   */
  @Test
  fun serverTruncatesRequestHttp2OnDuplexRequest() {
    enableProtocol(Protocol.HTTP_2)

    server.enqueue(
      MockResponse(
        body = "abc",
        socketPolicy = DoNotReadRequestBody(ErrorCode.NO_ERROR.httpCode),
      ),
    )

    val requestBody = AsyncRequestBody()

    val call =
      client.newCall(
        Request(
          url = server.url("/"),
          body = requestBody,
        ),
      )

    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("abc")
      val requestBodyOut = requestBody.takeSink()
      assertFailsWith<IOException> {
        SlowRequestBody.writeTo(requestBodyOut)
      }
      assertFailsWith<IOException> {
        requestBodyOut.close()
      }
    }

    // Confirm that the connection pool was not corrupted by making another call.
    makeSimpleCall()
  }

  @Test
  fun serverTruncatesRequestButTrailersCanStillBeReadHttp1() {
    serverTruncatesRequestButTrailersCanStillBeRead(http2 = false)
  }

  @Test
  fun serverTruncatesRequestButTrailersCanStillBeReadHttp2() {
    enableProtocol(Protocol.HTTP_2)
    serverTruncatesRequestButTrailersCanStillBeRead(http2 = true)
  }

  private fun serverTruncatesRequestButTrailersCanStillBeRead(http2: Boolean) {
    val mockResponse =
      MockResponse.Builder()
        .socketPolicy(DoNotReadRequestBody(ErrorCode.NO_ERROR.httpCode))
        .trailers(headersOf("caboose", "xyz"))

    // Trailers always work for HTTP/2, but only for chunked bodies in HTTP/1.
    if (http2) {
      mockResponse.body("abc")
    } else {
      mockResponse.chunkedBody("abc", 1)
    }

    server.enqueue(mockResponse.build())

    val call =
      client.newCall(
        Request(
          url = server.url("/"),
          body = SlowRequestBody,
        ),
      )

    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("abc")
      assertThat(response.trailers()).isEqualTo(headersOf("caboose", "xyz"))
    }
  }

  @Disabled("Follow up with fix in https://github.com/square/okhttp/issues/6853")
  @Test
  fun serverDisconnectsBeforeSecondRequestHttp1() {
    enableProtocol(Protocol.HTTP_1_1)

    server.enqueue(MockResponse(code = 200, body = "Req1"))
    server.enqueue(MockResponse(code = 200, body = "Req2"))

    val eventListener =
      object : EventListener() {
        var socket: SSLSocket? = null
        var closed = false

        override fun connectionAcquired(
          call: Call,
          connection: Connection,
        ) {
          socket = connection.socket() as SSLSocket
        }

        override fun requestHeadersStart(call: Call) {
          if (closed) {
            throw IOException("fake socket failure")
          }
        }
      }
    val localClient = client.newBuilder().eventListener(eventListener).build()

    val call1 = localClient.newCall(Request(server.url("/")))

    call1.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("Req1")
      assertThat(response.handshake).isNotNull()
      assertThat(response.protocol == Protocol.HTTP_1_1)
    }

    eventListener.closed = true

    val call2 = localClient.newCall(Request(server.url("/")))

    assertThrows<IOException>("fake socket failure") {
      call2.execute()
    }
  }

  @Test
  fun noAttemptToReadResponseIfLoadingRequestBodyIsSourceOfFailure() {
    server.enqueue(MockResponse(body = "abc"))

    val requestBody =
      object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun writeTo(sink: BufferedSink) {
          throw IOException("boom") // Despite this exception, 'sink' is healthy.
        }
      }

    val callA =
      client.newCall(
        Request(
          url = server.url("/"),
          body = requestBody,
        ),
      )

    assertFailsWith<IOException> {
      callA.execute()
    }.also { expected ->
      assertThat(expected).hasMessage("boom")
    }

    assertThat(server.requestCount).isEqualTo(0)

    // Confirm that the connection pool was not corrupted by making another call. This doesn't use
    // makeSimpleCall() because it uses the MockResponse enqueued above.
    val callB = client.newCall(Request(server.url("/")))
    callB.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("abc")
    }
  }

  private fun makeSimpleCall() {
    server.enqueue(MockResponse(body = "healthy"))
    val callB = client.newCall(Request(server.url("/")))
    callB.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("healthy")
    }
  }

  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client =
      client.newBuilder()
        .protocols(listOf(protocol, Protocol.HTTP_1_1))
        .build()
    server.protocols = client.protocols
  }

  private fun enableTls() {
    client =
      client.newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  /** A request body that slowly trickles bytes, expecting to not complete. */
  private object SlowRequestBody : RequestBody() {
    override fun contentType(): MediaType? = null

    override fun writeTo(sink: BufferedSink) {
      for (i in 0 until 50) {
        sink.writeUtf8("abc")
        sink.flush()
        Thread.sleep(100)
      }
      fail("")
    }
  }
}
