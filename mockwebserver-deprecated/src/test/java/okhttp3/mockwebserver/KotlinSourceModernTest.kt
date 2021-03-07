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
package okhttp3.mockwebserver

import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.WebSocketListener
import okhttp3.internal.http2.Settings
import okio.Buffer
import org.junit.Ignore
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Access every type, function, and property from Kotlin to defend against unexpected regressions in
 * modern 4.0.x kotlin source-compatibility.
 */
@Suppress(
    "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE",
    "UNUSED_ANONYMOUS_PARAMETER",
    "UNUSED_VALUE",
    "UNUSED_VARIABLE",
    "VARIABLE_WITH_REDUNDANT_INITIALIZER",
    "RedundantLambdaArrow",
    "RedundantExplicitType",
    "IMPLICIT_NOTHING_AS_TYPE_PARAMETER"
)
class KotlinSourceModernTest {
  @Test @Ignore
  fun dispatcherFromMockWebServer() {
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = TODO()
      override fun peek(): MockResponse = TODO()
      override fun shutdown() = TODO()
    }
  }

  @Test @Ignore
  fun mockResponse() {
    var mockResponse: MockResponse = MockResponse()
    var status: String = mockResponse.status
    status = mockResponse.status
    mockResponse.status = ""
    mockResponse = mockResponse.setResponseCode(0)
    var headers: Headers = mockResponse.headers
    var trailers: Headers = mockResponse.trailers
    mockResponse = mockResponse.clearHeaders()
    mockResponse = mockResponse.addHeader("")
    mockResponse = mockResponse.addHeader("", "")
    mockResponse = mockResponse.addHeaderLenient("", Any())
    mockResponse = mockResponse.setHeader("", Any())
    mockResponse.headers = headersOf()
    mockResponse.trailers = headersOf()
    mockResponse = mockResponse.removeHeader("")
    var body: Buffer? = mockResponse.getBody()
    mockResponse = mockResponse.setBody(Buffer())
    mockResponse = mockResponse.setChunkedBody(Buffer(), 0)
    mockResponse = mockResponse.setChunkedBody("", 0)
    var socketPolicy: SocketPolicy = mockResponse.socketPolicy
    mockResponse.socketPolicy = SocketPolicy.KEEP_OPEN
    var http2ErrorCode: Int = mockResponse.http2ErrorCode
    mockResponse.http2ErrorCode = 0
    mockResponse = mockResponse.throttleBody(0L, 0L, TimeUnit.SECONDS)
    var throttleBytesPerPeriod: Long = mockResponse.throttleBytesPerPeriod
    throttleBytesPerPeriod = mockResponse.throttleBytesPerPeriod
    var throttlePeriod: Long = mockResponse.getThrottlePeriod(TimeUnit.SECONDS)
    mockResponse = mockResponse.setBodyDelay(0L, TimeUnit.SECONDS)
    val bodyDelay: Long = mockResponse.getBodyDelay(TimeUnit.SECONDS)
    mockResponse = mockResponse.setHeadersDelay(0L, TimeUnit.SECONDS)
    val headersDelay: Long = mockResponse.getHeadersDelay(TimeUnit.SECONDS)
    mockResponse = mockResponse.withPush(PushPromise("", "", headersOf(), MockResponse()))
    var pushPromises: List<PushPromise> = mockResponse.pushPromises
    pushPromises = mockResponse.pushPromises
    mockResponse = mockResponse.withSettings(Settings())
    var settings: Settings = mockResponse.settings
    settings = mockResponse.settings
    mockResponse = mockResponse.withWebSocketUpgrade(object : WebSocketListener() {
    })
    var webSocketListener: WebSocketListener? = mockResponse.webSocketListener
    webSocketListener = mockResponse.webSocketListener
  }

  @Test @Ignore
  fun mockWebServer() {
    val mockWebServer: MockWebServer = MockWebServer()
    var port: Int = mockWebServer.port
    var hostName: String = mockWebServer.hostName
    hostName = mockWebServer.hostName
    val toProxyAddress: Proxy = mockWebServer.toProxyAddress()
    mockWebServer.serverSocketFactory = ServerSocketFactory.getDefault()
    val url: HttpUrl = mockWebServer.url("")
    mockWebServer.bodyLimit = 0L
    mockWebServer.protocolNegotiationEnabled = false
    mockWebServer.protocols = listOf()
    val protocols: List<Protocol> = mockWebServer.protocols
    mockWebServer.useHttps(SSLSocketFactory.getDefault() as SSLSocketFactory, false)
    mockWebServer.noClientAuth()
    mockWebServer.requestClientAuth()
    mockWebServer.requireClientAuth()
    val request: RecordedRequest = mockWebServer.takeRequest()
    val nullableRequest: RecordedRequest? = mockWebServer.takeRequest(0L, TimeUnit.SECONDS)
    var requestCount: Int = mockWebServer.requestCount
    mockWebServer.enqueue(MockResponse())
    mockWebServer.start()
    mockWebServer.start(0)
    mockWebServer.start(InetAddress.getLocalHost(), 0)
    mockWebServer.shutdown()
    var dispatcher: Dispatcher = mockWebServer.dispatcher
    dispatcher = mockWebServer.dispatcher
    mockWebServer.dispatcher = QueueDispatcher()
    mockWebServer.dispatcher = QueueDispatcher()
    mockWebServer.close()
  }

  @Test @Ignore
  fun pushPromise() {
    val pushPromise: PushPromise = PushPromise("", "", headersOf(), MockResponse())
    val method: String = pushPromise.method
    val path: String = pushPromise.path
    val headers: Headers = pushPromise.headers
    val response: MockResponse = pushPromise.response
  }

  @Test @Ignore
  fun queueDispatcher() {
    val queueDispatcher: QueueDispatcher = QueueDispatcher()
    var mockResponse: MockResponse = queueDispatcher.dispatch(
        RecordedRequest("", headersOf(), listOf(), 0L, Buffer(), 0, Socket()))
    mockResponse = queueDispatcher.peek()
    queueDispatcher.enqueueResponse(MockResponse())
    queueDispatcher.shutdown()
    queueDispatcher.setFailFast(false)
    queueDispatcher.setFailFast(MockResponse())
  }

  @Test @Ignore
  fun recordedRequest() {
    var recordedRequest: RecordedRequest = RecordedRequest(
        "", headersOf(), listOf(), 0L, Buffer(), 0, Socket())
    recordedRequest = RecordedRequest("", headersOf(), listOf(), 0L, Buffer(), 0, Socket())
    var requestUrl: HttpUrl? = recordedRequest.requestUrl
    var requestLine: String = recordedRequest.requestLine
    var method: String? = recordedRequest.method
    var path: String? = recordedRequest.path
    var headers: Headers = recordedRequest.headers
    val header: String? = recordedRequest.getHeader("")
    var chunkSizes: List<Int> = recordedRequest.chunkSizes
    var bodySize: Long = recordedRequest.bodySize
    var body: Buffer = recordedRequest.body
    var utf8Body: String = recordedRequest.body.readUtf8()
    var sequenceNumber: Int = recordedRequest.sequenceNumber
    var tlsVersion: TlsVersion? = recordedRequest.tlsVersion
    var handshake: Handshake? = recordedRequest.handshake
  }

  @Test @Ignore
  fun socketPolicy() {
    val socketPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN
  }
}
