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

import java.io.File
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
import okhttp3.ConnectionSpec.Builder
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion.TLS_1_2
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.ByteString.Companion.toByteString

/**
 * Logs SSL keys to /tmp/key.log, allowing Wireshark to decode traffic and be examined with http2
 * filter.
 */
class WireSharkKeyLoggerListener : EventListener() {
  var random: String? = null

  val loggerHandler = object : Handler() {
    override fun publish(record: LogRecord) {
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
//        p(record.message)
//        p(record.parameters[0])
        val param = parameters[0] as String

        if (message == "Produced ClientHello handshake message") {
          random = readClientRandom(param)
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
    logger.addHandler(loggerHandler)
  }

  override fun secureConnectEnd(
    call: Call,
    handshake: Handshake?
  ) {
    logger.removeHandler(loggerHandler)
  }

  override fun callEnd(call: Call) {
    // Cleanup log handler if failed.
    logger.removeHandler(loggerHandler)
  }

  override fun connectionAcquired(
    call: Call,
    connection: Connection
  ) {
    if (random != null) {
      val sslSocket = connection.socket() as SSLSocket
      val session = sslSocket.session

      val masterSecretHex = session.masterSecret?.encoded?.toByteString()
          ?.hex()
//          p(masterSecretHex)
//          p(session.id.toByteString().hex())

      if (masterSecretHex != null) {
        val keyLog = "CLIENT_RANDOM $random $masterSecretHex"

        println(keyLog)
        File("/tmp/key.log").appendText("$keyLog\n")
      }
    }

    random = null
  }

  class Factory : EventListener.Factory {
    override fun create(call: Call): EventListener {
      return WireSharkKeyLoggerListener()
    }
  }

  companion object {
    private lateinit var logger: Logger

    private val SSLSession.masterSecret: SecretKey?
      get() = javaClass.getDeclaredField("masterSecret")
          .apply {
            isAccessible = true
          }
          .get(this) as? SecretKey

    val randomRegex = "\"random\"\\s+:\\s+\"([^\"]+)\"".toRegex()

    fun register() {
      // Enable JUL logging for SSL events.
      System.setProperty("javax.net.debug", "")
      logger = Logger.getLogger("javax.net.ssl")
          .apply {
            level = Level.FINEST
          }
    }
  }
}

class WiresharkExample {
  val connectionSpec =
    Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TLS_1_2)
//        .cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256)
        .build()

  var bootstrapClient = OkHttpClient.Builder()
      .connectionSpecs(listOf(connectionSpec))
      .eventListenerFactory(WireSharkKeyLoggerListener.Factory())
      .build()

  val dns = DnsOverHttps.Builder().client(bootstrapClient)
    .url("https://1.1.1.1/dns-query".toHttpUrl())
    .includeIPv6(false)
    .build()

  val client = OkHttpClient.Builder()
      .connectionSpecs(listOf(connectionSpec))
      .eventListenerFactory(WireSharkKeyLoggerListener.Factory())
      .dns(dns)
      .build()

  fun run() {
    val fbRequest = Request.Builder()
        .url("https://graph.facebook.com/robots.txt?s=fb")
        .build()
    val twitterRequest = Request.Builder()
        .url("https://api.twitter.com/robots.txt?s=tw")
        .build()
    val googleRequest = Request.Builder()
        .url("https://www.google.com/robots.txt?s=g")
        .build()

    for (i in 1..2) {
      sendTestRequest(fbRequest)
      sendTestRequest(twitterRequest)
      sendTestRequest(googleRequest)

      Thread.sleep(500)
    }
  }

  private fun sendTestRequest(twitterRequest: Request) {
    client.newCall(twitterRequest)
        .execute()
        .use {
          val firstLine = it.body!!.string()
              .lines()
              .first()
          println("${it.code} ${it.request.url.host} $firstLine")
          Unit
        }
  }
}

fun main() {
  WireSharkKeyLoggerListener.register()

  val example = WiresharkExample()
  example.run()
}
