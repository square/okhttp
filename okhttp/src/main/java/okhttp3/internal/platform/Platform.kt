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

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.Util
import okhttp3.internal.tls.BasicCertificateChainCleaner
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import okio.Buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.NoSuchAlgorithmException
import java.security.Security
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Access to platform-specific features.
 *
 * <h3>Server name indication (SNI)</h3>
 *
 * Supported on Android 2.3+.
 *
 * Supported on OpenJDK 7+
 *
 * <h3>Session Tickets</h3>
 *
 * Supported on Android 2.3+.
 *
 * <h3>Android Traffic Stats (Socket Tagging)</h3>
 *
 * Supported on Android 4.0+.
 *
 * <h3>ALPN (Application Layer Protocol Negotiation)</h3>
 *
 * Supported on Android 5.0+. The APIs were present in Android 4.4, but that implementation was
 * unstable.
 *
 * Supported on OpenJDK 8 via the JettyALPN-boot library.
 *
 * Supported on OpenJDK 9+ via SSLParameters and SSLSocket features.
 *
 * <h3>Trust Manager Extraction</h3>
 *
 * Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an [SSLSocketFactory].
 *
 * <h3>Android Cleartext Permit Detection</h3>
 *
 * Supported on Android 6.0+ via `NetworkSecurityPolicy`.
 */
open class Platform {

  /** Prefix used on custom headers.  */
  fun getPrefix() = "OkHttp"

  open fun getSSLContext(): SSLContext = try {
    SSLContext.getInstance("TLS")
  } catch (e: NoSuchAlgorithmException) {
    throw IllegalStateException("No TLS provider", e)
  }

  protected open fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
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
   *
   * @param hostname non-null for client-side handshakes; null for server-side handshakes.
   */
  open fun configureTlsExtensions(
    sslSocket: SSLSocket, hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>
  ) {
  }

  /** Called after the TLS handshake to release resources allocated by [configureTlsExtensions]. */
  open fun afterHandshake(sslSocket: SSLSocket) {}

  /** Returns the negotiated protocol, or null if no protocol was negotiated.  */
  open fun getSelectedProtocol(socket: SSLSocket): String? = null

  @Throws(IOException::class)
  open fun connectSocket(socket: Socket, address: InetSocketAddress, connectTimeout: Int) {
    socket.connect(address, connectTimeout)
  }

  open fun log(level: Int, message: String, t: Throwable?) {
    val logLevel = if (level == WARN) Level.WARNING else Level.INFO
    logger.log(logLevel, message, t)
  }

  open fun isCleartextTrafficPermitted(hostname: String): Boolean = true

  /**
   * Returns an object that holds a stack trace created at the moment this method is executed. This
   * should be used specifically for [java.io.Closeable] objects and in conjunction with
   * [logCloseableLeak].
   */
  open fun getStackTraceForCloseable(closer: String): Any? =
      when {
        logger.isLoggable(Level.FINE) -> Throwable(closer) // These are expensive to allocate.
        else -> null
      }

  open fun logCloseableLeak(message: String, stackTrace: Any?) {
    var logMessage = message
    if (stackTrace == null) {
      logMessage += " To see where this was allocated, set the OkHttpClient logger level to FINE: " +
          "Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);"
    }
    log(WARN, logMessage, stackTrace as Throwable?)
  }

  open fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      BasicCertificateChainCleaner(buildTrustRootIndex(trustManager))

  fun buildCertificateChainCleaner(sslSocketFactory: SSLSocketFactory): CertificateChainCleaner {
    val trustManager = trustManager(sslSocketFactory) ?: throw IllegalStateException(
        "Unable to extract the trust manager on "
            + get()
            + ", sslSocketFactory is "
            + sslSocketFactory.javaClass)

    return buildCertificateChainCleaner(trustManager)
  }

  open fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex =
      BasicTrustRootIndex(*trustManager.acceptedIssuers)

  open fun configureSslSocketFactory(socketFactory: SSLSocketFactory) {}

  override fun toString(): String = javaClass.simpleName

  companion object {
    private val PLATFORM = findPlatform()

    const val INFO = 4
    const val WARN = 5

    private val logger = Logger.getLogger(OkHttpClient::class.java.name)

    @JvmStatic
    fun get(): Platform = PLATFORM

    fun alpnProtocolNames(protocols: List<Protocol>) =
        protocols.filter { it != Protocol.HTTP_1_0 }.map { it.toString() }

    // mainly to allow tests to run cleanly
    // check if Provider manually installed
    @JvmStatic
    val isConscryptPreferred: Boolean
      get() {
        if ("conscrypt" == Util.getSystemProperty("okhttp.platform", null)) {
          return true
        }
        val preferredProvider = Security.getProviders()[0].name
        return "Conscrypt" == preferredProvider
      }

    /** Attempt to match the host runtime to a capable Platform implementation.  */
    @JvmStatic
    private fun findPlatform(): Platform {
      val android = AndroidPlatform.buildIfSupported()

      if (android != null) {
        return android
      }

      if (isConscryptPreferred) {
        val conscrypt = ConscryptPlatform.buildIfSupported()

        if (conscrypt != null) {
          return conscrypt
        }
      }

      val jdk9 = Jdk9Platform.buildIfSupported()

      if (jdk9 != null) {
        return jdk9
      }

      val jdkWithJettyBoot = Jdk8WithJettyBootPlatform.buildIfSupported()

      return if (jdkWithJettyBoot != null) {
        jdkWithJettyBoot
      } else {
        // Probably an Oracle JDK like OpenJDK.
        Platform()
      }
    }

    /**
     * Returns the concatenation of 8-bit, length prefixed protocol names.
     * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
     */
    @JvmStatic
    fun concatLengthPrefixed(protocols: List<Protocol>): ByteArray {
      val result = Buffer()
      alpnProtocolNames(protocols).forEach { protocol ->
        result.writeByte(protocol.length)
        result.writeUtf8(protocol)
      }
      return result.readByteArray()
    }

    @JvmStatic
    fun <T> readFieldOrNull(instance: Any, fieldType: Class<T>, fieldName: String): T? {
      var c: Class<*> = instance.javaClass
      while (c != Any::class.java) {
        try {
          val field = c.getDeclaredField(fieldName)
          field.isAccessible = true
          val value = field.get(instance)
          return if (!fieldType.isInstance(value)) null else fieldType.cast(value)
        } catch (ignored: NoSuchFieldException) {
        } catch (e: IllegalAccessException) {
          throw AssertionError()
        }

        c = c.superclass
      }

      // Didn't find the field we wanted. As a last gasp attempt,
      // try to find the value on a delegate.
      if (fieldName != "delegate") {
        val delegate = readFieldOrNull(instance, Any::class.java, "delegate")
        if (delegate != null) return readFieldOrNull(delegate, fieldType, fieldName)
      }

      return null
    }
  }
}
