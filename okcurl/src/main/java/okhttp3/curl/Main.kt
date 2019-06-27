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

import com.github.rvesse.airline.HelpOption
import com.github.rvesse.airline.SingleCommand
import com.github.rvesse.airline.annotations.Arguments
import com.github.rvesse.airline.annotations.Command
import com.github.rvesse.airline.annotations.Option
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.curl.Main.Companion.NAME
import okhttp3.internal.format
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http2.Http2
import okhttp3.internal.platform.Platform
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import okio.sink
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Command(name = NAME, description = "A curl for the next-generation web.")
class Main : Runnable {
  @Inject
  var help: HelpOption<Main>? = null

  @Option(name = ["-X", "--request"], description = "Specify request command to use")
  var method: String? = null

  @Option(name = ["-d", "--data"], description = "HTTP POST data")
  var data: String? = null

  @Option(name = ["-H", "--header"], description = "Custom header to pass to server")
  var headers: MutableList<String>? = null

  @Option(name = ["-A", "--user-agent"], description = "User-Agent to send to server")
  var userAgent = NAME + "/" + versionString()

  @Option(name = ["--connect-timeout"],
      description = "Maximum time allowed for connection (seconds)")
  var connectTimeout = DEFAULT_TIMEOUT

  @Option(name = ["--read-timeout"],
      description = "Maximum time allowed for reading data (seconds)")
  var readTimeout = DEFAULT_TIMEOUT

  @Option(name = ["--call-timeout"],
      description = "Maximum time allowed for the entire call (seconds)")
  var callTimeout = DEFAULT_TIMEOUT

  @Option(name = ["-L", "--location"], description = "Follow redirects")
  var followRedirects: Boolean = false

  @Option(name = ["-k", "--insecure"], description = "Allow connections to SSL sites without certs")
  var allowInsecure: Boolean = false

  @Option(name = ["-i", "--include"], description = "Include protocol headers in the output")
  var showHeaders: Boolean = false

  @Option(name = ["--frames"], description = "Log HTTP/2 frames to STDERR")
  var showHttp2Frames: Boolean = false

  @Option(name = ["-e", "--referer"], description = "Referer URL")
  var referer: String? = null

  @Option(name = ["-V", "--version"], description = "Show version number and quit")
  var version: Boolean = false

  @Option(name = ["-v", "--verbose"], description = "Makes $NAME verbose during the operation")
  var verbose: Boolean = false

  @Arguments(title = ["url"], description = "Remote resource URL")
  var url: String? = null

  private lateinit var client: OkHttpClient

  override fun run() {
    if (help?.showHelpIfRequested() == true) {
      return
    }

    if (version) {
      println(NAME + " " + versionString())
      println("Protocols: " + protocols())
      return
    }

    if (showHttp2Frames) {
      enableHttp2FrameLogging()
    }

    client = createClient()
    val request = createRequest()

    if (request == null) {
      help?.showHelp()
      return
    }

    try {
      val response = client.newCall(request).execute()
      if (showHeaders) {
        println(StatusLine.get(response))
        val headers = response.headers
        for ((name, value) in headers) {
          println("$name: $value")
        }
        println()
      }

      // Stream the response to the System.out as it is returned from the server.
      val out = System.out.sink()
      val source = response.body!!.source()
      while (!source.exhausted()) {
        out.write(source.buffer, source.buffer.size)
        out.flush()
      }

      response.body!!.close()
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      close()
    }
  }

  private fun createClient(): OkHttpClient {
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
      val logger = object : HttpLoggingInterceptor.Logger {
        override fun log(message: String) {
          println(message)
        }
      }
      builder.eventListenerFactory(LoggingEventListener.Factory(logger))
    }
    return builder.build()
  }

  fun createRequest(): Request? {
    val request = Request.Builder()

    val requestUrl = url ?: return null
    val requestMethod = method ?: if (data != null) "POST" else "GET"

    request.url(requestUrl)

    data?.let {
      request.method(requestMethod, it.toRequestBody(mediaType()))
    }

    for (header in headers.orEmpty()) {
      val parts = header.split(':', limit = 2)
      request.header(parts[0], parts[1])
    }
    referer?.let {
      request.header("Referer", it)
    }
    request.header("User-Agent", userAgent)

    return request.build()
  }

  private fun mediaType(): MediaType? {
    val mimeType = headers?.let {
      for (header in it) {
        val parts = header.split(':', limit = 2)
        if ("Content-Type".equals(parts[0], ignoreCase = true)) {
          it.remove(header)
          return@let parts[1].trim()
        }
      }
      return@let null
    } ?: "application/x-www-form-urlencoded"

    return mimeType.toMediaTypeOrNull()
  }

  private fun close() {
    client.connectionPool.evictAll() // Close any persistent connections.
  }

  companion object {
    internal const val NAME = "okcurl"
    internal const val DEFAULT_TIMEOUT = -1
    private var frameLogger: Logger? = null

    @JvmStatic
    fun fromArgs(vararg args: String): Main =
        SingleCommand.singleCommand(Main::class.java).parse(*args)

    @JvmStatic
    fun main(args: Array<String>) {
      Main.fromArgs(*args).run()
    }

    private fun versionString(): String {
      val prop = Properties()
      Main::class.java.getResourceAsStream("/okcurl-version.properties").use {
        prop.load(it)
      }
      return prop.getProperty("version", "dev")
    }

    private fun protocols() = Protocol.values().joinToString(", ")

    private fun createInsecureTrustManager(): X509TrustManager = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

      override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

      override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun createInsecureSslSocketFactory(trustManager: TrustManager): SSLSocketFactory =
        Platform.get().newSSLContext().apply {
          init(null, arrayOf(trustManager), null)
        }.socketFactory

    private fun createInsecureHostnameVerifier(): HostnameVerifier =
        HostnameVerifier { _, _ -> true }

    private fun enableHttp2FrameLogging() {
      frameLogger = Logger.getLogger(Http2::class.java.name).apply {
        level = Level.FINE
        addHandler(ConsoleHandler().apply {
          level = Level.FINE
          formatter = object : SimpleFormatter() {
            override fun format(record: LogRecord): String {
              return format("%s%n", record.message)
            }
          }
        })
      }
    }
  }
}
