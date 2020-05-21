/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.platform

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.Security
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.platform.android.AndroidLog
import okhttp3.internal.readFieldOrNull
import okhttp3.internal.tls.BasicCertificateChainCleaner
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import okio.Buffer

/**
 * Access to platform-specific features.
 *
 * ### Session Tickets
 *
 * Supported on Android 2.3+.
 * Supported on JDK 8+ via Conscrypt.
 *
 * ### ALPN (Application Layer Protocol Negotiation)
 *
 * Supported on Android 5.0+.
 *
 * Supported on OpenJDK 8 via the JettyALPN-boot library or Conscrypt.
 *
 * Supported on OpenJDK 9+ via SSLParameters and SSLSocket features.
 *
 * ### Trust Manager Extraction
 *
 * Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an [SSLSocketFactory].
 *
 * Not supported by choice on JDK9+ due to access checks.
 *
 * ### Android Cleartext Permit Detection
 *
 * Supported on Android 6.0+ via `NetworkSecurityPolicy`.
 */
open class Platform {

  /** Prefix used on custom headers. */
  fun getPrefix() = "OkHttp"

  open fun newSSLContext(): SSLContext = SSLContext.getInstance("TLS")

  open fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val trustManagers = factory.trustManagers!!
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
      "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    return trustManagers[0] as X509TrustManager
  }

  open fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    return try {
      // Attempt to get the trust manager from an OpenJDK socket factory. We attempt this on all
      // platforms in order to support Robolectric, which mixes classes from both Android and the
      // Oracle JDK. Note that we don't support HTTP/2 or other nice features on Robolectric.
      val sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl")
      val context = readFieldOrNull(sslSocketFactory, sslContextClass, "context") ?: return null
      readFieldOrNull(context, X509TrustManager::class.java, "trustManager")
    } catch (e: ClassNotFoundException) {
      null
    }
  }

  /**
   * Configure TLS extensions on `sslSocket` for `route`.
   */
  open fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>
  ) {
  }

  /** Called after the TLS handshake to release resources allocated by [configureTlsExtensions]. */
  open fun afterHandshake(sslSocket: SSLSocket) {
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  open fun getSelectedProtocol(sslSocket: SSLSocket): String? = null

  @Throws(IOException::class)
  open fun connectSocket(socket: Socket, address: InetSocketAddress, connectTimeout: Int) {
    socket.connect(address, connectTimeout)
  }

  open fun log(message: String, level: Int = INFO, t: Throwable? = null) {
    val logLevel = if (level == WARN) Level.WARNING else Level.INFO
    logger.log(logLevel, message, t)
  }

  open fun isCleartextTrafficPermitted(hostname: String): Boolean = true

  /**
   * Returns an object that holds a stack trace created at the moment this method is executed. This
   * should be used specifically for [java.io.Closeable] objects and in conjunction with
   * [logCloseableLeak].
   */
  open fun getStackTraceForCloseable(closer: String): Any? {
    return when {
      logger.isLoggable(Level.FINE) -> Throwable(closer) // These are expensive to allocate.
      else -> null
    }
  }

  open fun logCloseableLeak(message: String, stackTrace: Any?) {
    var logMessage = message
    if (stackTrace == null) {
      logMessage += " To see where this was allocated, set the OkHttpClient logger level to " +
          "FINE: Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);"
    }
    log(logMessage, WARN, stackTrace as Throwable?)
  }

  open fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      BasicCertificateChainCleaner(buildTrustRootIndex(trustManager))

  open fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex =
      BasicTrustRootIndex(*trustManager.acceptedIssuers)

  open fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
    try {
      return newSSLContext().apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
      }.socketFactory
    } catch (e: GeneralSecurityException) {
      throw AssertionError("No System TLS: $e", e) // The system has no TLS. Just give up.
    }
  }

  override fun toString(): String = javaClass.simpleName

  companion object {
    @Volatile private var platform = findPlatform()

    const val INFO = 4
    const val WARN = 5

    private val logger = Logger.getLogger(OkHttpClient::class.java.name)

    @JvmStatic
    fun get(): Platform = platform

    fun resetForTests(platform: Platform = findPlatform()) {
      this.platform = platform
    }

    fun alpnProtocolNames(protocols: List<Protocol>) =
        protocols.filter { it != Protocol.HTTP_1_0 }.map { it.toString() }

    // This explicit check avoids activating in Android Studio with Android specific classes
    // available when running plugins inside the IDE.
    val isAndroid: Boolean
        get() = "Dalvik" == System.getProperty("java.vm.name")

    private val isConscryptPreferred: Boolean
      get() {
        val preferredProvider = Security.getProviders()[0].name
        return "Conscrypt" == preferredProvider
      }

    private val isOpenJSSEPreferred: Boolean
      get() {
        val preferredProvider = Security.getProviders()[0].name
        return "OpenJSSE" == preferredProvider
      }

    private val isBouncyCastlePreferred: Boolean
      get() {
        val preferredProvider = Security.getProviders()[0].name
        return "BC" == preferredProvider
      }

    /** Attempt to match the host runtime to a capable Platform implementation. */
    private fun findPlatform(): Platform = if (isAndroid) {
      findAndroidPlatform()
    } else {
      findJvmPlatform()
    }

    private fun findAndroidPlatform(): Platform {
      AndroidLog.enable()
      return Android10Platform.buildIfSupported() ?: AndroidPlatform.buildIfSupported()!!
    }

    private fun findJvmPlatform(): Platform {
      if (isConscryptPreferred) {
        val conscrypt = ConscryptPlatform.buildIfSupported()

        if (conscrypt != null) {
          return conscrypt
        }
      }

      if (isBouncyCastlePreferred) {
        val bc = BouncyCastlePlatform.buildIfSupported()

        if (bc != null) {
          return bc
        }
      }

      if (isOpenJSSEPreferred) {
        val openJSSE = OpenJSSEPlatform.buildIfSupported()

        if (openJSSE != null) {
          return openJSSE
        }
      }

      // An Oracle JDK 9 like OpenJDK, or JDK 8 251+.
      val jdk9 = Jdk9Platform.buildIfSupported()

      if (jdk9 != null) {
        return jdk9
      }

      // An Oracle JDK 8 like OpenJDK, pre 251.
      val jdkWithJettyBoot = Jdk8WithJettyBootPlatform.buildIfSupported()

      if (jdkWithJettyBoot != null) {
        return jdkWithJettyBoot
      }

      return Platform()
    }

    /**
     * Returns the concatenation of 8-bit, length prefixed protocol names.
     * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
     */
    fun concatLengthPrefixed(protocols: List<Protocol>): ByteArray {
      val result = Buffer()
      for (protocol in alpnProtocolNames(protocols)) {
        result.writeByte(protocol.length)
        result.writeUtf8(protocol)
      }
      return result.readByteArray()
    }
  }
}
