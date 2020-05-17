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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion.TLS_1_2
import okio.ByteString.Companion.toByteString

private val SSLSession.masterSecret: SecretKey
  get() = javaClass.getDeclaredField("masterSecret").apply {
    isAccessible = true
  }.get(this) as SecretKey

val randomRegex = "\"random\"\\s+:\\s+\"([^\"]+)\"".toRegex()

class WiresharkExample {
  var random: String? = null
  var logNext = false

  val connectionSpec =
    Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TLS_1_2)
//        .cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256)
        .build()
  val client = OkHttpClient.Builder()
      .connectionSpecs(listOf(connectionSpec))
      .eventListener(KeyLoggerListener())
      .build()

  fun run() {
    val url = "https://graph.facebook.com/robots.txt"
    val logger = Logger.getLogger("javax.net.ssl").apply {
      level = Level.FINEST
      addHandler(KeyLoggerHandler())
    }

    val request = Request.Builder().url(url).build()

    while (true) {
      client.newCall(request)
          .execute()
          .use { response ->
            println(response.body!!.string())
          }

      Thread.sleep(5000)
    }
  }

  inner class KeyLoggerListener : EventListener() {
    override fun secureConnectEnd(
      call: Call,
      handshake: Handshake?
    ) {
      logNext = true
    }

    override fun connectionAcquired(
      call: Call,
      connection: Connection
    ) {
      if (logNext) {
        logNext = false

        val sslSocket = connection.socket() as SSLSocket
        val session = sslSocket.session

        val masterSecretHex = session.masterSecret.encoded.toByteString()
            .hex()
//          println(masterSecretHex)
//          println(session.id.toByteString().hex())

        val keyLog = "CLIENT_RANDOM $random $masterSecretHex"
        println(keyLog)
        File("/tmp/key.log").appendText("$keyLog\n")
      }
    }
  }

  inner class KeyLoggerHandler : Handler() {
    override fun publish(record: LogRecord) {
      // https://timothybasanov.com/2016/05/26/java-pre-master-secret.html
      // https://security.stackexchange.com/questions/35639/decrypting-tls-in-wireshark-when-using-dhe-rsa-ciphersuites
      // https://stackoverflow.com/questions/36240279/how-do-i-extract-the-pre-master-secret-using-an-openssl-based-client

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
        println(record.message)
        println(record.parameters[0])
        val param = parameters[0] as String

        if (message == "Produced ClientHello handshake message") {
          random = readClientRandom(param)
        }
      }
    }

    private fun readClientRandom(param: String): String? {
      val matchResult = randomRegex.find(param)

      return if (matchResult != null) {
        matchResult.groupValues[1].replace(" ", "")
      } else {
        null
      }
    }

    override fun flush() {}

    override fun close() {}
  }
}

fun main() {
  // Enable JUL logging for SSL events.
  System.setProperty("javax.net.debug", "")
  val example = WiresharkExample()
  example.run()
}
