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
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.OkHttpTrustManager
import okhttp3.Request
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.internal.TlsUtil

class DevServerAndroid {
  val handshakeCertificates = TlsUtil.localhost()

  val server = MockWebServer().apply {
    useHttps(handshakeCertificates.sslSocketFactory(), false)

    enqueue(MockResponse()
        .setResponseCode(HTTP_MOVED_TEMP)
        .setHeader("Location", "https://www.google.com/robots.txt"))
  }

  val hosts = arrayOf(server.hostName)

  val platformTrustManager = platformTrustManager()
  val trustManager = OkHttpTrustManager.create(platformTrustManager) { hosts.contains(it) }
  val sslSocketFactory = Platform.get().newSSLContext().apply {
    init(null, arrayOf(trustManager), null)
  }.socketFactory

  val client = OkHttpClient.Builder()
      .sslSocketFactory(sslSocketFactory, trustManager)
      .build()

  fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers!![0] as X509TrustManager
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
  DevServerAndroid().run()
}
