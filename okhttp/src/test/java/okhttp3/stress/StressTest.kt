/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.stress

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.http2.Http2
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Make continuous calls to MockWebServer and print the ongoing QPS and failure count.
 *
 * This can be used with tools like Envoy.
 */
class StressTest(
  private val maxConcurrentRequests: Int = 100,
  private val logsPerSecond: Int = 10,
  private val protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
  private val listenPortOverride: Int? = null,
  private val connectPortOverride: Int? = null,
  requestBodySize: Int = 128,
  responseBodySize: Int = 128
) : Dispatcher(), Callback {
  private lateinit var heldCertificate: HeldCertificate
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient
  private val statsCollector = StatsCollector()
  private val responseBodyBytes: ByteString
  private val requestBodyBytes: ByteString

  init {
    val random = Random(0)

    val responseBodyByteArray = ByteArray(responseBodySize)
    random.nextBytes(responseBodyByteArray)
    responseBodyBytes = responseBodyByteArray.toByteString()

    val requestBodyByteArray = ByteArray(requestBodySize)
    random.nextBytes(requestBodyByteArray)
    requestBodyBytes = requestBodyByteArray.toByteString()
  }

  private fun prepareHeldCertificate() {
    val certificate = """
        |-----BEGIN CERTIFICATE-----
        |MIIBbzCCARSgAwIBAgIBATAKBggqhkjOPQQDAjAvMS0wKwYDVQQDEyRlNjUwMzY2
        |Yi1kYzk3LTQxZjMtYTg5Ni1mZjMzOTU1ZWExNjgwHhcNMTkwOTA0MjAxMTQ1WhcN
        |MTkwOTA1MjAxMTQ1WjAvMS0wKwYDVQQDEyRlNjUwMzY2Yi1kYzk3LTQxZjMtYTg5
        |Ni1mZjMzOTU1ZWExNjgwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATW9EKXvV6G
        |+97SPJtipU+8r6Fgwtj8djKoHtf5AGA0ajNgV5IbtASYeOeMcpvrpix4hEh6Rqzz
        |OnowkT2EHGyJoyEwHzAdBgNVHREBAf8EEzARgg9qZXNzZXRlc3QubG9jYWwwCgYI
        |KoZIzj0EAwIDSQAwRgIhAOpPmREAn+dcqPKHR8X2gNa1xrlvHz2AVqe6oaS+RU0s
        |AiEAgUzxGK83LLrUxxO2V5GyZtVBUVWswoalEqC9h56lnPI=
        |-----END CERTIFICATE-----
        |""".trimMargin()

    val privateKey = """
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAVrOCnn68DKtYVYxFc
        |VfgZIWj/Q0PcBbiC5jRw8E4qoQ==
        |-----END PRIVATE KEY-----
        |""".trimMargin()

    if (true) {
      heldCertificate = HeldCertificate.decode(certificate + privateKey)
    } else {
      // TODO(jwilson): make this easier.
      heldCertificate = HeldCertificate.Builder()
          .addSubjectAlternativeName("jessetest.local")
          .duration(365 * 100, TimeUnit.DAYS)
          .build()
    }
  }

  private fun prepareServer() {
    val mockWebServerLogger = Logger.getLogger(MockWebServer::class.java.name)
    mockWebServerLogger.level = Level.SEVERE
    val http2Logger = Logger.getLogger(Http2::class.java.name)
    http2Logger.level = Level.FINE
    val consoleHandler = ConsoleHandler()
    consoleHandler.level = Level.FINE
    http2Logger.addHandler(consoleHandler)

    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()

    server = MockWebServer()
    server.dispatcher = this
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    server.protocols = protocols
    server.start(listenPortOverride ?: 0)
  }

  private fun prepareClient() {
    val handshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

    client = OkHttpClient.Builder()
        .protocols(protocols)
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(),
            handshakeCertificates.trustManager
        )
        .dispatcher(okhttp3.Dispatcher().apply {
          maxRequests = maxConcurrentRequests
          maxRequestsPerHost = maxConcurrentRequests
        })
        .dns(object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            return when (hostname) {
              "jessetest.local" -> listOf(InetAddress.getByName("localhost"))
              else -> Dns.SYSTEM.lookup(hostname)
            }
          }
        })
        .addNetworkInterceptor(object : Interceptor {
          override fun intercept(chain: Interceptor.Chain): Response {
            statsCollector.addConnection(chain.connection())
            return chain.proceed(chain.request())
          }
        })
        .build()
  }

  override fun dispatch(request: RecordedRequest): MockResponse {
    val requestBodySize = request.body.size
    request.body.clear()

    val responseBodyBuffer = Buffer().write(responseBodyBytes)

    val response = MockResponse()
    response.setBody(responseBodyBuffer)

    statsCollector.addStats(Stats(
        serverResponses = 1,
        serverResponseBytes = responseBodyBytes.size.toLong(),
        serverRequestBytes = requestBodySize
    ))

    return response
  }

  private fun enqueueCall() {
    val requestBody = requestBodyBytes.toRequestBody()
    val httpUrl = "https://jessetest.local/foo".toHttpUrl().newBuilder()
        .port(connectPortOverride ?: server.port)
        .build()
    val call = client.newCall(Request.Builder()
        .url(httpUrl)
        .method("POST", requestBody)
        .build())

    statsCollector.addStats(Stats(
        clientRequests = 1,
        clientRequestBytes = requestBody.contentLength()
    ))

    call.enqueue(this)
  }

  override fun onFailure(call: Call, e: IOException) {
    statsCollector.addStats(Stats(
        clientExceptions = 1
    ))

    enqueueCall()
  }

  override fun onResponse(call: Call, response: Response) {
    try {
      response.use {
        val buffer = Buffer()
        response.body!!.source().readAll(buffer)
        val responseBodySize = buffer.size
        buffer.clear()

        statsCollector.addStats(Stats(
            clientResponses = (if (response.isSuccessful) 1 else 0),
            clientFailures = (if (response.isSuccessful) 0 else 1),
            clientResponseBytes = responseBodySize,
            clientHttp1Responses = (if (response.protocol == Protocol.HTTP_1_1) 1 else 0),
            clientHttp2Responses = (if (response.protocol == Protocol.HTTP_2) 1 else 0)
        ))
      }
    } catch (e: IOException) {
      statsCollector.addStats(Stats(
          clientExceptions = 1
      ))
    }

    enqueueCall()
  }

  fun run() {
    statsCollector.addStats(Stats())

    prepareHeldCertificate()
    prepareServer()
    prepareClient()

    for (i in 0 until maxConcurrentRequests) {
      enqueueCall()
    }

    statsCollector.printStatsContinuously(logsPerSecond)
  }
}

fun main() {
  StressTest(
      maxConcurrentRequests = 3,
      logsPerSecond = 3,
      listenPortOverride = 4444,
      connectPortOverride = 10000,
      requestBodySize = 512 * 1024,
      responseBodySize = 512 * 1024
  ).run()
}
