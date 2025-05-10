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
package okhttp3.logging

import assertk.assertThat
import assertk.assertions.isNotNull
import java.io.IOException
import java.net.UnknownHostException
import java.util.Arrays
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.FailHandshake
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@Suppress("ktlint:standard:max-line-length")
@ExtendWith(MockWebServerExtension::class)
class LoggingEventListenerTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private lateinit var server: MockWebServer
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private val logRecorder =
    HttpLoggingInterceptorTest.LogRecorder(
      prefix = Regex("""\[\d+ ms] """),
    )
  private val loggingEventListenerFactory = LoggingEventListener.Factory(logRecorder)
  private lateinit var client: OkHttpClient
  private lateinit var url: HttpUrl

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    client =
      clientTestRule
        .newClientBuilder()
        .eventListenerFactory(loggingEventListenerFactory)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).retryOnConnectionFailure(false)
        .build()
    url = server.url("/")
  }

  @Test
  fun get() {
    assumeNotWindows()
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello!")
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    assertThat(response.body).isNotNull()
    response.body.bytes()
    logRecorder
      .assertLogMatch(Regex("""callStart: Request\{method=GET, url=$url\}"""))
      .assertLogMatch(Regex("""proxySelectStart: $url"""))
      .assertLogMatch(Regex("""proxySelectEnd: \[DIRECT]"""))
      .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
      .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
      .assertLogMatch(Regex("""connectStart: ${url.host}/.+ DIRECT"""))
      .assertLogMatch(Regex("""connectEnd: http/1.1"""))
      .assertLogMatch(
        Regex(
          """connectionAcquired: Connection\{${url.host}:\d+, proxy=DIRECT hostAddress=${url.host}/.+ cipherSuite=none protocol=http/1\.1\}""",
        ),
      ).assertLogMatch(Regex("""requestHeadersStart"""))
      .assertLogMatch(Regex("""requestHeadersEnd"""))
      .assertLogMatch(Regex("""responseHeadersStart"""))
      .assertLogMatch(Regex("""responseHeadersEnd: Response\{protocol=http/1\.1, code=200, message=OK, url=$url\}"""))
      .assertLogMatch(Regex("""responseBodyStart"""))
      .assertLogMatch(Regex("""responseBodyEnd: byteCount=6"""))
      .assertLogMatch(Regex("""connectionReleased"""))
      .assertLogMatch(Regex("""callEnd"""))
      .assertNoMoreLogs()
  }

  @Test
  fun post() {
    assumeNotWindows()
    server.enqueue(MockResponse())
    client.newCall(request().post("Hello!".toRequestBody(PLAIN)).build()).execute()
    logRecorder
      .assertLogMatch(Regex("""callStart: Request\{method=POST, url=$url\}"""))
      .assertLogMatch(Regex("""proxySelectStart: $url"""))
      .assertLogMatch(Regex("""proxySelectEnd: \[DIRECT]"""))
      .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
      .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
      .assertLogMatch(Regex("""connectStart: ${url.host}/.+ DIRECT"""))
      .assertLogMatch(Regex("""connectEnd: http/1.1"""))
      .assertLogMatch(
        Regex(
          """connectionAcquired: Connection\{${url.host}:\d+, proxy=DIRECT hostAddress=${url.host}/.+ cipherSuite=none protocol=http/1\.1\}""",
        ),
      ).assertLogMatch(Regex("""requestHeadersStart"""))
      .assertLogMatch(Regex("""requestHeadersEnd"""))
      .assertLogMatch(Regex("""requestBodyStart"""))
      .assertLogMatch(Regex("""requestBodyEnd: byteCount=6"""))
      .assertLogMatch(Regex("""responseHeadersStart"""))
      .assertLogMatch(Regex("""responseHeadersEnd: Response\{protocol=http/1\.1, code=200, message=OK, url=$url\}"""))
      .assertLogMatch(Regex("""responseBodyStart"""))
      .assertLogMatch(Regex("""responseBodyEnd: byteCount=0"""))
      .assertLogMatch(Regex("""connectionReleased"""))
      .assertLogMatch(Regex("""callEnd"""))
      .assertNoMoreLogs()
  }

  @Test
  fun secureGet() {
    assumeNotWindows()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    url = server.url("/")
    server.enqueue(MockResponse())
    val response = client.newCall(request().build()).execute()
    assertThat(response.body).isNotNull()
    response.body.bytes()
    platform.assumeHttp2Support()
    logRecorder
      .assertLogMatch(Regex("""callStart: Request\{method=GET, url=$url\}"""))
      .assertLogMatch(Regex("""proxySelectStart: $url"""))
      .assertLogMatch(Regex("""proxySelectEnd: \[DIRECT]"""))
      .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
      .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
      .assertLogMatch(Regex("""connectStart: ${url.host}/.+ DIRECT"""))
      .assertLogMatch(Regex("""secureConnectStart"""))
      .assertLogMatch(
        Regex(
          """secureConnectEnd: Handshake\{tlsVersion=TLS_1_[23] cipherSuite=TLS_.* peerCertificates=\[CN=localhost] localCertificates=\[]\}""",
        ),
      ).assertLogMatch(Regex("""connectEnd: h2"""))
      .assertLogMatch(
        Regex("""connectionAcquired: Connection\{${url.host}:\d+, proxy=DIRECT hostAddress=${url.host}/.+ cipherSuite=.+ protocol=h2\}"""),
      ).assertLogMatch(Regex("""requestHeadersStart"""))
      .assertLogMatch(Regex("""requestHeadersEnd"""))
      .assertLogMatch(Regex("""responseHeadersStart"""))
      .assertLogMatch(Regex("""responseHeadersEnd: Response\{protocol=h2, code=200, message=, url=$url\}"""))
      .assertLogMatch(Regex("""responseBodyStart"""))
      .assertLogMatch(Regex("""responseBodyEnd: byteCount=0"""))
      .assertLogMatch(Regex("""connectionReleased"""))
      .assertLogMatch(Regex("""callEnd"""))
      .assertNoMoreLogs()
  }

  @Test
  fun dnsFail() {
    client =
      OkHttpClient
        .Builder()
        .dns { _ -> throw UnknownHostException("reason") }
        .eventListenerFactory(loggingEventListenerFactory)
        .build()
    try {
      client.newCall(request().build()).execute()
      fail<Any>()
    } catch (expected: UnknownHostException) {
    }
    logRecorder
      .assertLogMatch(Regex("""callStart: Request\{method=GET, url=$url\}"""))
      .assertLogMatch(Regex("""proxySelectStart: $url"""))
      .assertLogMatch(Regex("""proxySelectEnd: \[DIRECT]"""))
      .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
      .assertLogMatch(Regex("""callFailed: java.net.UnknownHostException: reason"""))
      .assertNoMoreLogs()
  }

  @Test
  fun connectFail() {
    assumeNotWindows()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse
        .Builder()
        .socketPolicy(FailHandshake)
        .build(),
    )
    url = server.url("/")
    try {
      client.newCall(request().build()).execute()
      fail<Any>()
    } catch (expected: IOException) {
    }
    logRecorder
      .assertLogMatch(Regex("""callStart: Request\{method=GET, url=$url\}"""))
      .assertLogMatch(Regex("""proxySelectStart: $url"""))
      .assertLogMatch(Regex("""proxySelectEnd: \[DIRECT]"""))
      .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
      .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
      .assertLogMatch(Regex("""connectStart: ${url.host}/.+ DIRECT"""))
      .assertLogMatch(Regex("""secureConnectStart"""))
      .assertLogMatch(
        Regex(
          """connectFailed: null \S+(?:SSLProtocolException|SSLHandshakeException|TlsFatalAlert): .*(?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error|Handshake failed|unexpected_message\(10\)).*""",
        ),
      ).assertLogMatch(
        Regex(
          """callFailed: \S+(?:SSLProtocolException|SSLHandshakeException|TlsFatalAlert): .*(?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error|Handshake failed|unexpected_message\(10\)).*""",
        ),
      ).assertNoMoreLogs()
  }

  @Test
  fun testCacheEvents() {
    val request = Request.Builder().url(url).build()
    val call = client.newCall(request)
    val response =
      Response
        .Builder()
        .request(request)
        .code(200)
        .message("")
        .protocol(Protocol.HTTP_2)
        .build()
    val listener = loggingEventListenerFactory.create(call)
    listener.cacheConditionalHit(call, response)
    listener.cacheHit(call, response)
    listener.cacheMiss(call)
    listener.satisfactionFailure(call, response)
    logRecorder
      .assertLogMatch(Regex("""cacheConditionalHit: Response\{protocol=h2, code=200, message=, url=$url\}"""))
      .assertLogMatch(Regex("""cacheHit: Response\{protocol=h2, code=200, message=, url=$url\}"""))
      .assertLogMatch(Regex("""cacheMiss"""))
      .assertLogMatch(Regex("""satisfactionFailure: Response\{protocol=h2, code=200, message=, url=$url\}"""))
      .assertNoMoreLogs()
  }

  private fun request(): Request.Builder = Request.Builder().url(url)

  companion object {
    private val PLAIN = "text/plain".toMediaType()
  }
}
