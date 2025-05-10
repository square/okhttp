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
@file:Suppress("Since15")

package okhttp3.recipes.kt

import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.crypto.SecretKey
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.TlsVersion.TLS_1_2
import okhttp3.TlsVersion.TLS_1_3
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.recipes.kt.WireSharkListenerFactory.WireSharkKeyLoggerListener.Launch
import okhttp3.recipes.kt.WireSharkListenerFactory.WireSharkKeyLoggerListener.Launch.CommandLine
import okhttp3.recipes.kt.WireSharkListenerFactory.WireSharkKeyLoggerListener.Launch.Gui
import okio.ByteString.Companion.toByteString

/**
 * Logs SSL keys to a log file, allowing Wireshark to decode traffic and be examined with http2
 * filter. The approach is to hook into JSSE log events for the messages between client and server
 * during handshake, and then take the agreed masterSecret from private fields of the session.
 *
 * Copy WireSharkKeyLoggerListener to your test code to use in development.
 *
 * This logs TLSv1.2 on a JVM (OpenJDK 11+) without any additional code.  For TLSv1.3
 * an existing external tool is required.
 *
 * See https://stackoverflow.com/questions/61929216/how-to-log-tlsv1-3-keys-in-jsse-for-wireshark-to-decode-traffic
 *
 * Steps to run in your own code
 *
 * 1. In your main method `WireSharkListenerFactory.register()`
 * 2. Create Listener factory `val eventListenerFactory = WireSharkListenerFactory(
logFile = File("/tmp/key.log"), tlsVersions = tlsVersions, launch = launch)`
 * 3. Register with `client.eventListenerFactory(eventListenerFactory)`
 * 4. Launch wireshark if not done externally `val process = eventListenerFactory.launchWireShark()`
 */
@SuppressSignatureCheck
class WireSharkListenerFactory(
  private val logFile: File,
  private val tlsVersions: List<TlsVersion>,
  private val launch: Launch? = null,
) : EventListener.Factory {
  override fun create(call: Call): EventListener = WireSharkKeyLoggerListener(logFile, launch == null)

  fun launchWireShark(): Process? {
    when (launch) {
      null -> {
        if (tlsVersions.contains(TLS_1_2)) {
          println("TLSv1.2 traffic will be logged automatically and available via wireshark")
        }

        if (tlsVersions.contains(TLS_1_3)) {
          println("TLSv1.3 requires an external command run before first traffic is sent")
          println("Follow instructions at https://github.com/neykov/extract-tls-secrets for TLSv1.3")
          println("Pid: ${ProcessHandle.current().pid()}")

          Thread.sleep(10000)
        }
      }
      CommandLine -> {
        return ProcessBuilder(
          "tshark",
          "-l",
          "-V",
          "-o",
          "tls.keylog_file:$logFile",
          "-Y",
          "http2",
          "-O",
          "http2,tls",
        ).redirectInput(File("/dev/null"))
          .redirectOutput(Redirect.INHERIT)
          .redirectError(Redirect.INHERIT)
          .start()
      }
      Gui -> {
        return ProcessBuilder(
          "nohup",
          "wireshark",
          "-o",
          "tls.keylog_file:$logFile",
          "-S",
          "-l",
          "-Y",
          "http2",
          "-k",
        ).redirectInput(File("/dev/null"))
          .redirectOutput(File("/dev/null"))
          .redirectError(Redirect.INHERIT)
          .start()
          .also {
            // Give it time to start collecting
            Thread.sleep(2000)
          }
      }
    }

    return null
  }

  class WireSharkKeyLoggerListener(
    private val logFile: File,
    private val verbose: Boolean = false,
  ) : EventListener() {
    var random: String? = null
    lateinit var currentThread: Thread

    private val loggerHandler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          // Try to avoid multi threading issues with concurrent requests
          if (Thread.currentThread() != currentThread) {
            return
          }

          // https://timothybasanov.com/2016/05/26/java-pre-master-secret.html
          // https://security.stackexchange.com/questions/35639/decrypting-tls-in-wireshark-when-using-dhe-rsa-ciphersuites
          // https://stackoverflow.com/questions/36240279/how-do-i-extract-the-pre-master-secret-using-an-openssl-based-client

          // TLSv1.2 Events
          // Produced ClientHello handshake message
          // Consuming ServerHello handshake message
          // Consuming server Certificate handshake message
          // Consuming server CertificateStatus handshake message
          // Found trusted certificate
          // Consuming ECDH ServerKeyExchange handshake message
          // Consuming ServerHelloDone handshake message
          // Produced ECDHE ClientKeyExchange handshake message
          // Produced client Finished handshake message
          // Consuming server Finished handshake message
          // Produced ClientHello handshake message
          //
          // Raw write
          // Raw read
          // Plaintext before ENCRYPTION
          // Plaintext after DECRYPTION
          val message = record.message
          val parameters = record.parameters

          if (parameters != null && !message.startsWith("Raw") && !message.startsWith("Plaintext")) {
            if (verbose) {
              println(record.message)
              println(record.parameters[0])
            }

            // JSSE logs additional messages as parameters that are not referenced in the log message.
            val parameter = parameters[0] as String

            if (message == "Produced ClientHello handshake message") {
              random = readClientRandom(parameter)
            }
          }
        }

        override fun flush() {}

        override fun close() {}
      }

    private fun readClientRandom(param: String): String? {
      val matchResult = randomRegex.find(param)

      return if (matchResult != null) {
        matchResult.groupValues[1].replace(" ", "")
      } else {
        null
      }
    }

    override fun secureConnectStart(call: Call) {
      // Register to capture "Produced ClientHello handshake message".
      currentThread = Thread.currentThread()
      logger.addHandler(loggerHandler)
    }

    override fun secureConnectEnd(
      call: Call,
      handshake: Handshake?,
    ) {
      logger.removeHandler(loggerHandler)
    }

    override fun callEnd(call: Call) {
      // Cleanup log handler if failed.
      logger.removeHandler(loggerHandler)
    }

    override fun connectionAcquired(
      call: Call,
      connection: Connection,
    ) {
      if (random != null) {
        val sslSocket = connection.socket() as SSLSocket
        val session = sslSocket.session

        val masterSecretHex =
          session.masterSecret
            ?.encoded
            ?.toByteString()
            ?.hex()

        if (masterSecretHex != null) {
          val keyLog = "CLIENT_RANDOM $random $masterSecretHex"

          if (verbose) {
            println(keyLog)
          }
          logFile.appendText("$keyLog\n")
        }
      }

      random = null
    }

    enum class Launch {
      Gui,
      CommandLine,
    }
  }

  companion object {
    private lateinit var logger: Logger

    private val SSLSession.masterSecret: SecretKey?
      get() =
        javaClass
          .getDeclaredField("masterSecret")
          .apply {
            isAccessible = true
          }.get(this) as? SecretKey

    val randomRegex = "\"random\"\\s+:\\s+\"([^\"]+)\"".toRegex()

    fun register() {
      // Enable JUL logging for SSL events, must be activated early or via -D option.
      System.setProperty("javax.net.debug", "")
      logger =
        Logger
          .getLogger("javax.net.ssl")
          .apply {
            level = Level.FINEST
            useParentHandlers = false
          }
    }
  }
}

@SuppressSignatureCheck
class WiresharkExample(
  tlsVersions: List<TlsVersion>,
  private val launch: Launch? = null,
) {
  private val connectionSpec =
    ConnectionSpec
      .Builder(ConnectionSpec.RESTRICTED_TLS)
      .tlsVersions(*tlsVersions.toTypedArray())
      .build()

  private val eventListenerFactory =
    WireSharkListenerFactory(
      logFile = File("/tmp/key.log"),
      tlsVersions = tlsVersions,
      launch = launch,
    )

  val client =
    OkHttpClient
      .Builder()
      .connectionSpecs(listOf(connectionSpec))
      .eventListenerFactory(eventListenerFactory)
      .build()

  fun run() {
    // Launch wireshark in the background
    val process = eventListenerFactory.launchWireShark()

    val fbRequest =
      Request
        .Builder()
        .url("https://graph.facebook.com/robots.txt?s=fb")
        .build()
    val twitterRequest =
      Request
        .Builder()
        .url("https://api.twitter.com/robots.txt?s=tw")
        .build()
    val googleRequest =
      Request
        .Builder()
        .url("https://www.google.com/robots.txt?s=g")
        .build()

    try {
      for (i in 1..2) {
        // Space out traffic to make it easier to demarcate.
        sendTestRequest(fbRequest)
        Thread.sleep(1000)
        sendTestRequest(twitterRequest)
        Thread.sleep(1000)
        sendTestRequest(googleRequest)
        Thread.sleep(2000)
      }
    } finally {
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdownNow()

      if (launch == CommandLine) {
        process?.destroyForcibly()
      }
    }
  }

  private fun sendTestRequest(request: Request) {
    try {
      if (this.launch != CommandLine) {
        println(request.url)
      }

      client
        .newCall(request)
        .execute()
        .use {
          val firstLine =
            it.body
              .string()
              .lines()
              .first()
          if (this.launch != CommandLine) {
            println("${it.code} ${it.request.url.host} $firstLine")
          }
          Unit
        }
    } catch (e: IOException) {
      System.err.println(e)
    }
  }
}

fun main() {
  // Call this before anything else initialises the JSSE stack.
  WireSharkListenerFactory.register()

  val example = WiresharkExample(tlsVersions = listOf(TLS_1_2), launch = CommandLine)
  example.run()
}
