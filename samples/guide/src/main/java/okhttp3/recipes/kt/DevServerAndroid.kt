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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.internal.TlsUtil

class AndroidAllowlistedTrustManager(
  private val delegate: X509TrustManager,
  private vararg val hosts: String
) : X509TrustManager {
  val delegateMethod = lookupDelegateMethod()

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
    delegate.checkClientTrusted(chain, authType)
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    throw CertificateException("Unsupported operation")
  }

  /**
   * Android method to clean and sort certificates, called via reflection.
   */
  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {

    if (isAllowed(host)) {
      println("Skipping security checks for $host")
      println(chain.map { it.subjectDN.name })

      return listOf()
    }

    println("Running security checks for $host")
    println(chain.map { it.subjectDN.name }.take(1))

    if (delegateMethod != null) {
      return invokeDelegateMethod(delegateMethod, chain, authType, host)
    }

    throw CertificateException("Failed to call checkServerTrusted")
  }

  fun isAllowed(host: String): Boolean = hosts.contains(host)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  private fun lookupDelegateMethod(): Method? {
    return try {
      delegate.javaClass.getMethod("checkServerTrusted",
          Array<X509Certificate>::class.java, String::class.java, String::class.java)
    } catch (nsme: NoSuchMethodException) {
      null
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun invokeDelegateMethod(
    delegateMethod: Method,
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    try {
      return delegateMethod.invoke(delegate, chain, authType, host) as List<Certificate>
    } catch (ite: InvocationTargetException) {
      throw ite.targetException
    }
  }
}

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
  val trustManager = AndroidAllowlistedTrustManager(platformTrustManager, *hosts)
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
