/*
 * Copyright (C) 2025 Square, Inc.
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
import assertk.assertions.isGreaterThan
import assertk.assertions.isIn
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.lang.Thread.sleep
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import okhttp3.testing.PlatformRule
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import okio.use
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Timeout(30)
open class TrailersTest {
  private val fileSystem = FakeFileSystem()

  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private var client =
    clientTestRule
      .newClientBuilder()
      .cache(Cache(fileSystem, "/cache/".toPath(), Long.MAX_VALUE))
      .build()

  @Test
  fun trailersHttp1() {
    trailers(Protocol.HTTP_1_1)
  }

  @Test
  fun trailersHttp2() {
    trailers(Protocol.HTTP_2)
  }

  private fun trailers(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("h1", "v1")
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      val source = response.body.source()
      assertThat(response.header("h1")).isEqualTo("v1")
      assertThat(source.readUtf8()).isEqualTo("Hello")
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2")) // Idempotent.
    }
  }

  @Test
  fun trailersEmptyResponseBodyHttp1() {
    trailersEmptyResponseBody(Protocol.HTTP_1_1)
  }

  @Test
  fun trailersEmptyResponseBodyHttp2() {
    trailersEmptyResponseBody(Protocol.HTTP_2)
  }

  private fun trailersEmptyResponseBody(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      val source = response.body.source()
      assertThat(source.readUtf8()).isEqualTo("")
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
    }
  }

  @Test
  fun trailersWithoutReadingFullResponseBodyHttp1() {
    trailersWithoutReadingFullResponseBody(Protocol.HTTP_1_1)
  }

  @Test
  fun trailersWithoutReadingFullResponseBodyHttp2() {
    trailersWithoutReadingFullResponseBody(Protocol.HTTP_2)
  }

  private fun trailersWithoutReadingFullResponseBody(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
      assertThat(response.body.source().exhausted()).isTrue()
    }
  }

  @Test
  @Disabled
  fun trailersAndCacheHttp1() {
    trailersAndCache(Protocol.HTTP_1_1)
  }

  @Test
  @Disabled
  fun trailersAndCacheHttp2() {
    trailersAndCache(Protocol.HTTP_2)
  }

  private fun trailersAndCache(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("h1", "v1")
        .addHeader("Cache-Control: max-age=30")
        .body(protocol, "Hello")
        .trailers(headersOf("t1", "v2"))
        .build(),
    )

    val call1 = client.newCall(Request(server.url("/")))
    call1.execute().use { response ->
      val source = response.body.source()
      assertThat(response.header("h1")).isEqualTo("v1")
      assertThat(source.readUtf8()).isEqualTo("Hello")
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
    }

    val call2 = client.newCall(Request(server.url("/")))
    call2.execute().use { response ->
      val source = response.body.source()
      assertThat(response.header("h1")).isEqualTo("v1")
      assertThat(source.readUtf8()).isEqualTo("Hello")
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
    }
  }

  @Test
  fun delayBeforeTrailersHttp1() {
    delayBeforeTrailers(Protocol.HTTP_1_1)
  }

  @Test
  fun delayBeforeTrailersHttp2() {
    trailers(Protocol.HTTP_2)
  }

  /** Confirm the client will block if necessary to consume trailers. */
  private fun delayBeforeTrailers(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .trailersDelay(500, TimeUnit.MILLISECONDS)
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      val source = response.body.source()
      assertThat(source.readUtf8(5)).isEqualTo("Hello")
      val trailersDelay =
        measureTime {
          val trailers = response.trailers()
          assertThat(trailers).isEqualTo(headersOf("t1", "v2"))
        }
      assertThat(trailersDelay).isGreaterThan(250.milliseconds)
    }
  }

  @Test
  fun disconnectBeforeTrailersHttp1() {
    disconnectBeforeTrailers(Protocol.HTTP_1_1)
  }

  @Test
  fun disconnectBeforeTrailersHttp2() {
    disconnectBeforeTrailers(Protocol.HTTP_2)
  }

  /** Confirm we can get an [IOException] reading trailers. */
  private fun disconnectBeforeTrailers(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .socketPolicy(SocketPolicy.DisconnectDuringResponseBody)
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertFailsWith<IOException> {
        response.trailers()
      }
    }
  }

  @Test
  fun cannotReadTrailersAfterEarlyResponseCloseHttp1() {
    cannotReadTrailersAfterEarlyResponseClose(Protocol.HTTP_1_1)
  }

  @Test
  fun cannotReadTrailersAfterEarlyResponseCloseHttp2() {
    cannotReadTrailersAfterEarlyResponseClose(Protocol.HTTP_2)
  }

  private fun cannotReadTrailersAfterEarlyResponseClose(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .bodyDelay(1, TimeUnit.SECONDS)
        .body(protocol, "Hello")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      response.close()
      assertFailsWith<IOException> {
        response.trailers()
      }
    }
  }

  @Test
  fun readTrailersAfterEarlyEofAndCloseHttp1() {
    readTrailersAfterEarlyEofAndClose(Protocol.HTTP_1_1)
  }

  @Test
  fun readTrailersAfterEarlyEofAndCloseHttp2() {
    readTrailersAfterEarlyEofAndClose(Protocol.HTTP_2)
  }

  private fun readTrailersAfterEarlyEofAndClose(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertThat(response.body.source().readUtf8()).isEqualTo("Hello")
      response.body.source().close()
      assertThat(response.trailers()).isEqualTo(headersOf("t1", "v2"))
    }
  }

  @Test
  fun readEmptyTrailersHttp1EmptyFixedLengthResponse() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertThat(response.body.source().readUtf8()).isEqualTo("")
      assertThat(response.trailers()).isEqualTo(Headers.EMPTY)
    }
  }

  @Test
  fun readEmptyTrailersHttp1NonEmptyFixedLengthResponse() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello")
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertThat(response.body.source().readUtf8()).isEqualTo("Hello")
      assertThat(response.trailers()).isEqualTo(Headers.EMPTY)
    }
  }

  @Test
  fun readEmptyTrailersHttp1UnknownLengthResponse() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello")
        .removeHeader("Content-Length")
        .socketPolicy(SocketPolicy.DisconnectAtEnd)
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      assertThat(response.headers["Content-Length"]).isNull()
      assertThat(response.body.source().readUtf8()).isEqualTo("Hello")
      assertThat(response.trailers()).isEqualTo(Headers.EMPTY)
    }
  }

  @Test
  fun cancelWhileReadingTrailersHttp1() {
    cancelWhileReadingTrailers(Protocol.HTTP_1_1)
  }

  @Test
  fun cancelWhileReadingTrailersHttp2() {
    cancelWhileReadingTrailers(Protocol.HTTP_2)
  }

  private fun cancelWhileReadingTrailers(protocol: Protocol) {
    enableProtocol(protocol)

    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("h1", "v1")
        .trailers(headersOf("t1", "v2"))
        .body(protocol, "Hello")
        .trailersDelay(1, TimeUnit.SECONDS)
        .build(),
    )

    val call = client.newCall(Request(server.url("/")))
    call.execute().use { response ->
      val source = response.body.source()
      assertThat(response.header("h1")).isEqualTo("v1")
      assertThat(source.readUtf8(5)).isEqualTo("Hello")
      call.cancelLater(500.milliseconds)
      val trailersDelay =
        measureTime {
          val exception =
            assertFailsWith<IOException> {
              response.trailers()
            }
          assertThat(exception.message).isIn(
            "Socket closed", // HTTP/1.1
            "stream was reset: CANCEL", // HTTP/2
          )
        }
      assertThat(trailersDelay).isGreaterThan(250.milliseconds)
      assertThat(trailersDelay).isLessThan(750.milliseconds)
    }
  }

  private fun MockResponse.Builder.body(
    protocol: Protocol,
    body: String,
  ) = apply {
    when (protocol) {
      Protocol.HTTP_1_1 -> chunkedBody(body)
      else -> body(body)
    }
  }

  private fun enableProtocol(protocol: Protocol) {
    if (protocol == Protocol.HTTP_2) {
      enableTls()
      client =
        client
          .newBuilder()
          .protocols(listOf(protocol, Protocol.HTTP_1_1))
          .build()
      server.protocols = client.protocols
    }
  }

  private fun enableTls() {
    val handshakeCertificates = platform.localhostHandshakeCertificates()
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  private fun Call.cancelLater(delay: Duration) {
    thread(name = "canceler") {
      sleep(delay.inWholeMilliseconds)
      cancel()
    }
  }
}
