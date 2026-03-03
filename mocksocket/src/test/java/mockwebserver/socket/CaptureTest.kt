@file:OptIn(ExperimentalStdlibApi::class)

package mockwebserver.socket

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.lang.reflect.Field
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.crypto.SecretKey
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.CipherSuite
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CaptureTest {
  private lateinit var logger: Logger
  private lateinit var keyLogOut: BufferedSink
  private var random: String? = null
  private val fileNetLog = "build/reports/test-netlog.json".toPath()
  private var filePcap = "build/reports/test-capture-1.pcap".toPath()
  private val keyLog = "build/reports/keylog.txt".toPath()
  val fileSystem = FileSystem.SYSTEM

  private val loggerHandler =
    object : Handler() {
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

  @BeforeEach
  fun setUp() {
    // Enable JUL logging for SSL events, must be activated early or via -D option.
    System.setProperty("javax.net.debug", "")
    logger =
      Logger
        .getLogger("javax.net.ssl")
        .apply {
          level = Level.FINEST
          useParentHandlers = false
        }
    logger.addHandler(loggerHandler)

    fileSystem.createDirectory(keyLog.parent!!)
    var i = 1
    while (fileSystem.exists(filePcap)) {
      i++
      filePcap = "build/reports/test-capture-$i.pcap".toPath()
    }

    keyLogOut = fileSystem.appendingSink(keyLog).buffer()

    // Enable JUL logging for SSL events, must be activated early or via -D option.
    logger =
      Logger
        .getLogger("javax.net.ssl")
        .apply {
          level = Level.FINEST
          useParentHandlers = false
        }
  }

  @AfterEach
  fun tearDown() {
    // Leave files for manual inspection if needed, or delete.
  }

  @Test
  fun exportPcapAndNetlog(): Unit =
    runBlocking {
      // Compose multiple listeners so both pcap and netlog can be generated in a single pass.
      val netLogRecorder = NetLogRecorder(file = fileNetLog)
      val pcapRecorder = PcapRecorder(file = filePcap)
      val multiListener =
        object : SocketEventListener {
          override fun onEvent(event: SocketEvent) {
            netLogRecorder.onEvent(event)
            pcapRecorder.onEvent(event)
          }
        }

      try {
        val connectionSpec =
          ConnectionSpec
            .Builder(ConnectionSpec.MODERN_TLS)
//          .cipherSuites(
//            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
//            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
//            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
//            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
//            CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
//          )
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()

        val client =
          OkHttpClient
            .Builder()
            .socketFactory(RecordingSocketFactory(socketEventListener = multiListener))
            .connectionSpecs(listOf(connectionSpec))
            .addInterceptor {
              it.proceed(
                it
                  .request()
                  .newBuilder()
                  .header("Accept-Encoding", "identity")
                  .build(),
              )
            }.eventListener(
              object : EventListener() {
                override fun secureConnectEnd(
                  call: Call,
                  handshake: Handshake?,
                ) {
                  super.secureConnectEnd(call, handshake)
                  println(handshake)
                }

                override fun connectionAcquired(
                  call: Call,
                  connection: Connection,
                ) {
                  if (connection.handshake() != null) {
                    val sslSocket = connection.socket() as SSLSocket
                    val session = sslSocket.session as ExtendedSSLSession
                    logKey(session)
                  }
                }
              },
            ).build()

        client.newCall(Request("https://google.com/robots.txt".toHttpUrl())).execute().use {
          it.body.string()
        }

        client.newCall(Request("https://github.com/robots.txt".toHttpUrl())).execute().use {
          it.body.string()
        }
      } finally {
        netLogRecorder.close()
        pcapRecorder.close()
      }

      // Verify traces got written
      assertThat(fileSystem.exists(fileNetLog)).isTrue()
      assertThat(fileSystem.metadata(fileNetLog).size).isNotNull().isGreaterThan(100L)

      assertThat(fileSystem.exists(filePcap)).isTrue()
      assertThat(fileSystem.metadata(filePcap).size).isNotNull().isGreaterThan(100L)
    }

  private val masterSecretField: Field =
    run {
      val clazz = Class.forName("sun.security.ssl.SSLSessionImpl")
      val field = clazz.getDeclaredField("masterSecret")
      field.isAccessible = true
      field
    }

  private fun logKey(session: ExtendedSSLSession) {
    val masterSecret = masterSecretField.get(session) as SecretKey?

    if (masterSecret != null) {
      val masterSecretHex = masterSecret.encoded.toHexString(HexFormat.Default)

      if (random != null) {
        keyLogOut
          .writeUtf8("CLIENT_RANDOM $random $masterSecretHex\n")
          .flush()
      }

      val id = session.id
      keyLogOut
        .writeUtf8("RSA Session-ID:${id.toHexString(HexFormat.Default)} Master-Key:$masterSecretHex\n")
        .flush()
    }
  }

  val randomRegex = "\"random\"\\s+:\\s+\"([^\"]+)\"".toRegex()

  private fun readClientRandom(param: String): String? {
    val matchResult = randomRegex.find(param)

    return if (matchResult != null) {
      matchResult.groupValues[1].replace(" ", "")
    } else {
      null
    }
  }

  companion object {
    init {
      System.setProperty("javax.net.debug", "")
    }
  }
}
