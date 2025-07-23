/*
 * Copyright (C) 2025 Block, Inc.
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

import java.io.IOException
import java.util.logging.Logger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.internal.connection.RealCall
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slow")
class CallDecoratorTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension
  public val clientTestRule =
    OkHttpClientTestRule().apply {
      logger = Logger.getLogger(CallDecoratorTest::class.java.name)
    }

  @StartStop
  private val server = MockWebServer()

  private val handshakeCertificates = localhost()

  @Test
  fun testSecureRequest() {
    server.enqueue(MockResponse())

    val request = Request.Builder().url(server.url("/")).build()

    val client: OkHttpClient =
      clientTestRule
        .newClientBuilder()
        .enableTls()
        .addCallDecorator(AlwaysHttps)
        .build()

    client.newCall(request).execute().use {
      assertEquals(200, it.code)
    }
  }

  @Test
  fun testInsecureRequestChangedToSecure() {
    server.enqueue(MockResponse())

    val request =
      Request
        .Builder()
        .url(
          server
            .url("/")
            .newBuilder()
            .scheme("http")
            .build(),
        ).build()

    val client: OkHttpClient =
      clientTestRule
        .newClientBuilder()
        .enableTls()
        .addCallDecorator(AlwaysHttps)
        .build()

    client.newCall(request).execute().use {
      assertEquals(200, it.code)
      assertEquals("https", it.request.url.scheme)
    }
  }

  class WrappedCall(
    delegate: Call,
  ) : Call by delegate

  @Test
  fun testWrappedCallIsObserved() {
    server.enqueue(MockResponse())

    val client: OkHttpClient =
      clientTestRule
        .newClientBuilder()
        .addCallDecorator { chain, request ->
          // First Call.Decorator will see the result of later decorators
          chain.newCall(request).also {
            if (it !is WrappedCall) {
              throw IOException("expecting wrapped call")
            }
            if (it.request().tag<String>() != "wrapped") {
              throw IOException("expecting tag1")
            }
          }
        }.addCallDecorator { chain, request ->
          // Wrap here
          val updatedRequest = request.newBuilder().tag<String>("wrapped").build()
          WrappedCall(chain.newCall(updatedRequest))
        }.addCallDecorator { chain, request ->
          // Updated requests are seen
          if (request.tag<String>() != "wrapped") {
            throw IOException("expecting tag2")
          }
          chain.newCall(request).also {
            // But Call is RealCall
            if (it !is RealCall) {
              throw IOException("expecting RealCall")
            }
          }
        }.addInterceptor { chain ->
          // Updated requests are seen in interceptors
          if (chain.request().tag<String>() != "wrapped") {
            throw IOException("expecting tag3")
          }
          chain.proceed(chain.request())
        }.addNetworkInterceptor { chain ->
          // and network interceptors
          if (chain.request().tag<String>() != "wrapped") {
            throw IOException("expecting tag4")
          }
          chain.proceed(chain.request())
        }.build()

    val originalRequest = Request.Builder().url(server.url("/")).build()
    client.newCall(originalRequest).execute().use {
      assertEquals(200, it.code)
    }
  }

  @Test
  fun testCanShortCircuit() {
    server.enqueue(MockResponse())

    val request = Request.Builder().url(server.url("/")).build()

    val client: OkHttpClient =
      clientTestRule
        .newClientBuilder()
        .build()

    val redirectingClient: OkHttpClient =
      clientTestRule
        .newClientBuilder()
        .addCallDecorator { _, request ->
          // Use the other client
          client.newCall(request)
        }.addInterceptor {
          // Fail if we get here
          throw IOException("You shall not pass")
        }.build()

    redirectingClient.newCall(request).execute().use {
      assertEquals(200, it.code)
    }
  }

  private fun OkHttpClient.Builder.enableTls(): OkHttpClient.Builder {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    return sslSocketFactory(
      handshakeCertificates.sslSocketFactory(),
      handshakeCertificates.trustManager,
    )
  }
}

private object AlwaysHttps : Call.Decorator {
  override fun newCall(
    chain: Call.Factory,
    request: Request,
  ): Call {
    val updatedRequest =
      if (request.url.scheme == "http") {
        request
          .newBuilder()
          .url(
            request.url
              .newBuilder()
              .scheme("https")
              .build(),
          ).build()
      } else {
        request
      }

    return chain.newCall(updatedRequest)
  }
}
