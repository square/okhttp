/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.curl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.TimeUnit.SECONDS
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.curl.internal.commonCreateRequest
import okhttp3.curl.internal.commonRun
import okhttp3.curl.logging.LoggingUtil
import okhttp3.internal.platform.Platform
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener

class Main : CliktCommand(name = NAME, help = "A curl for the next-generation web.") {
  val method: String? by option("-X", "--request", help = "Specify request command to use")

  val data: String? by option("-d", "--data", help = "HTTP POST data")

  val headers: List<String>? by option("-H", "--header", help = "Custom header to pass to server").multiple()

  val userAgent: String by option("-A", "--user-agent", help = "User-Agent to send to server").default(NAME + "/" + versionString())

  val connectTimeout: Int by option(
    "--connect-timeout",
    help = "Maximum time allowed for connection (seconds)",
  ).int().default(DEFAULT_TIMEOUT)

  val readTimeout: Int by option("--read-timeout", help = "Maximum time allowed for reading data (seconds)").int().default(DEFAULT_TIMEOUT)

  val callTimeout: Int by option(
    "--call-timeout",
    help = "Maximum time allowed for the entire call (seconds)",
  ).int().default(DEFAULT_TIMEOUT)

  val followRedirects: Boolean by option("-L", "--location", help = "Follow redirects").flag()

  val allowInsecure: Boolean by option("-k", "--insecure", help = "Allow connections to SSL sites without certs").flag()

  val showHeaders: Boolean by option("-i", "--include", help = "Include protocol headers in the output").flag()

  val showHttp2Frames: Boolean by option("--frames", help = "Log HTTP/2 frames to STDERR").flag()

  val referer: String? by option("-e", "--referer", help = "Referer URL")

  val verbose: Boolean by option("-v", "--verbose", help = "Makes $NAME verbose during the operation").flag()

  val sslDebug: Boolean by option(help = "Output SSL Debug").flag()

  val url: String? by argument(name = "url", help = "Remote resource URL")

  var client: Call.Factory? = null

  override fun run() {
    LoggingUtil.configureLogging(debug = verbose, showHttp2Frames = showHttp2Frames, sslDebug = sslDebug)

    commonRun()
  }

  fun createRequest(): Request = commonCreateRequest()

  fun createClient(): Call.Factory {
    val builder = OkHttpClient.Builder()
    builder.followSslRedirects(followRedirects)
    if (connectTimeout != DEFAULT_TIMEOUT) {
      builder.connectTimeout(connectTimeout.toLong(), SECONDS)
    }
    if (readTimeout != DEFAULT_TIMEOUT) {
      builder.readTimeout(readTimeout.toLong(), SECONDS)
    }
    if (callTimeout != DEFAULT_TIMEOUT) {
      builder.callTimeout(callTimeout.toLong(), SECONDS)
    }
    if (allowInsecure) {
      val trustManager = createInsecureTrustManager()
      val sslSocketFactory = createInsecureSslSocketFactory(trustManager)
      builder.sslSocketFactory(sslSocketFactory, trustManager)
      builder.hostnameVerifier(createInsecureHostnameVerifier())
    }
    if (verbose) {
      val logger = HttpLoggingInterceptor.Logger(::println)
      builder.eventListenerFactory(LoggingEventListener.Factory(logger))
    }
    return builder.build()
  }

  fun close() {
    val okHttpClient = client as OkHttpClient
    okHttpClient.connectionPool.evictAll() // Close any persistent connections.
    okHttpClient.dispatcher.executorService.shutdownNow()
  }

  companion object {
    internal const val NAME = "okcurl"
    internal const val DEFAULT_TIMEOUT = -1

    private fun versionString(): String? {
      val prop = Properties()
      Main::class.java.getResourceAsStream("/okcurl-version.properties")?.use {
        prop.load(it)
      }
      return prop.getProperty("version", "dev")
    }

    private fun createInsecureTrustManager(): X509TrustManager =
      object : X509TrustManager {
        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) {}

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }

    private fun createInsecureSslSocketFactory(trustManager: TrustManager): SSLSocketFactory =
      Platform.get().newSSLContext().apply {
        init(null, arrayOf(trustManager), null)
      }.socketFactory

    private fun createInsecureHostnameVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }
  }
}
