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
package okhttp3.recipes.kt

import java.io.IOException
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.internal.TlsUtil
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

@IgnoreJRERequirement
class JvmAllowlistedTrustManager(
  private val delegate: X509ExtendedTrustManager,
  private vararg val hosts: String
) : X509ExtendedTrustManager() {
  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
    throw CertificateException("Unsupported client operation")
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>?,
    authType: String?,
    engine: SSLEngine?
  ) {
    throw CertificateException("Unsupported client operation")
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>?,
    authType: String?,
    socket: Socket?
  ) {
    throw CertificateException("Unsupported client operation")
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    throw CertificateException("Unsupported operation")
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    val host = socket.inetAddress.hostName

    if (isAllowed(host)) {
      println("Skipping security checks for $host")
      println(chain.map { it.subjectDN.name })
    } else {
      println("Running security checks for $host")
      println(chain.map { it.subjectDN.name }.take(1))
      delegate.checkServerTrusted(chain, authType, socket)
    }
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    val host = engine.peerHost

    if (isAllowed(host)) {
      println("Skipping security checks for $host")
      println(chain.map { it.subjectDN.name })
    } else {
      println("Running security checks for $host")
      println(chain.map { it.subjectDN.name }.take(1))

      delegate.checkServerTrusted(chain, authType, engine)
    }
  }

  fun isAllowed(host: String): Boolean = hosts.contains(host)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

@IgnoreJRERequirement
class DevServerJvm {
  val handshakeCertificates = TlsUtil.localhost()

  val server = MockWebServer().apply {
    useHttps(handshakeCertificates.sslSocketFactory(), false)

    enqueue(MockResponse()
        .setResponseCode(HTTP_MOVED_TEMP)
        .setHeader("Location", "https://www.google.com/robots.txt"))
  }

  val hosts = arrayOf(server.hostName)

  val platformTrustManager = platformTrustManager()
  val trustManager = JvmAllowlistedTrustManager(platformTrustManager, *hosts)
  val sslSocketFactory = Platform.get().newSSLContext().apply {
    init(null, arrayOf(trustManager), null)
  }.socketFactory

  val client = OkHttpClient.Builder()
      .sslSocketFactory(sslSocketFactory, trustManager)
      .build()

  fun platformTrustManager(): X509ExtendedTrustManager {
    val factory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers!![0] as X509ExtendedTrustManager
  }

  fun run() {
    try {
      val request = Request.Builder()
          .url(server.url("/"))
          .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        println(response.request.url)
      }
    } finally {
      server.shutdown()
    }
  }
}

fun main() {
  DevServerJvm().run()
}
