/*
 * Copyright (C) 2016 Square, Inc.
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

import android.os.Build
import android.util.Log
import okhttp3.Protocol
import okhttp3.internal.Util
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Android 5+.  */
class AndroidPlatform(
  private val sslParametersClass: Class<*>,
  private val sslSocketClass: Class<*>,
  private val setUseSessionTickets: Method,
  private val setHostname: Method,
  private val getAlpnSelectedProtocol: Method,
  private val setAlpnProtocols: Method
) : Platform() {
  private val closeGuard = CloseGuard.get()

  // Not a real Android runtime; probably RoboVM or MoE
  // Try to load TLS 1.2 explicitly.
  // fallback to TLS
  override fun getSSLContext(): SSLContext {
    val tryTls12: Boolean = try {
      Build.VERSION.SDK_INT in 16..21
    } catch (e: NoClassDefFoundError) {
      true
    }

    if (tryTls12) {
      try {
        return SSLContext.getInstance("TLSv1.2")
      } catch (e: NoSuchAlgorithmException) {
      }
    }

    try {
      return SSLContext.getInstance("TLS")
    } catch (e: NoSuchAlgorithmException) {
      throw IllegalStateException("No TLS provider", e)
    }
  }

  @Throws(IOException::class)
  override fun connectSocket(
    socket: Socket, address: InetSocketAddress,
    connectTimeout: Int
  ) {
    try {
      socket.connect(address, connectTimeout)
    } catch (e: AssertionError) {
      if (Util.isAndroidGetsocknameError(e)) throw IOException(e)
      throw e
    } catch (e: ClassCastException) {
      // On android 8.0, socket.connect throws a ClassCastException due to a bug
      // see https://issuetracker.google.com/issues/63649622
      if (Build.VERSION.SDK_INT == 26) {
        throw IOException("Exception in connect", e)
      } else {
        throw e
      }
    }
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    var context: Any? =
        readFieldOrNull(sslSocketFactory, sslParametersClass, "sslParameters")
    if (context == null) {
      // If that didn't work, try the Google Play Services SSL provider before giving up. This
      // must be loaded by the SSLSocketFactory's class loader.
      try {
        val gmsSslParametersClass = Class.forName(
            "com.google.android.gms.org.conscrypt.SSLParametersImpl", false,
            sslSocketFactory.javaClass.classLoader)
        context = readFieldOrNull(sslSocketFactory, gmsSslParametersClass,
            "sslParameters")
      } catch (e: ClassNotFoundException) {
        return super.trustManager(sslSocketFactory)
      }
    }

    val x509TrustManager = readFieldOrNull(
        context!!, X509TrustManager::class.java, "x509TrustManager")
    return x509TrustManager ?: readFieldOrNull(context, X509TrustManager::class.java,
        "trustManager")
  }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket, hostname: String?, protocols: List<Protocol>
  ) {
    if (!sslSocketClass.isInstance(sslSocket)) {
      return  // No TLS extensions if the socket class is custom.
    }
    try {
      // Enable SNI and session tickets.
      if (hostname != null) {
        setUseSessionTickets.invoke(sslSocket, true)
        // This is SSLParameters.setServerNames() in API 24+.
        setHostname.invoke(sslSocket, hostname)
      }

      // Enable ALPN.
      setAlpnProtocols.invoke(sslSocket, concatLengthPrefixed(protocols))
    } catch (e: IllegalAccessException) {
      throw AssertionError(e)
    } catch (e: InvocationTargetException) {
      throw AssertionError(e)
    }
  }

  override fun getSelectedProtocol(socket: SSLSocket): String? {
    return if (sslSocketClass.isInstance(socket))
      try {
        val alpnResult = getAlpnSelectedProtocol.invoke(socket) as ByteArray?
        if (alpnResult != null) String(alpnResult, UTF_8) else null
      } catch (e: IllegalAccessException) {
        throw AssertionError(e)
      } catch (e: InvocationTargetException) {
        throw AssertionError(e)
      }
    else {
      null // No TLS extensions if the socket class is custom.
    }
  }

  override fun log(level: Int, message: String, t: Throwable?) {
    var logMessage = message
    val logLevel = if (level == WARN) Log.WARN else Log.DEBUG
    if (t != null) logMessage = logMessage + '\n'.toString() + Log.getStackTraceString(t)

    // Split by line, then ensure each line can fit into Log's maximum length.
    var i = 0
    val length = logMessage.length
    while (i < length) {
      var newline = logMessage.indexOf('\n', i)
      newline = if (newline != -1) newline else length
      do {
        val end = Math.min(newline, i + MAX_LOG_LENGTH)
        Log.println(logLevel, "OkHttp", logMessage.substring(i, end))
        i = end
      } while (i < newline)
      i++
    }
  }

  override fun getStackTraceForCloseable(closer: String): Any? = closeGuard.createAndOpen(closer)

  override fun logCloseableLeak(message: String, stackTrace: Any?) {
    val reported = closeGuard.warnIfOpen(stackTrace)
    if (!reported) {
      // Unable to report via CloseGuard. As a last-ditch effort, send it to the logger.
      log(WARN, message, null)
    }
  }

  override fun isCleartextTrafficPermitted(hostname: String): Boolean {
    return try {
      val networkPolicyClass = Class.forName("android.security.NetworkSecurityPolicy")
      val getInstanceMethod = networkPolicyClass.getMethod("getInstance")
      val networkSecurityPolicy = getInstanceMethod.invoke(null)
      api24IsCleartextTrafficPermitted(hostname, networkPolicyClass, networkSecurityPolicy)
    } catch (e: ClassNotFoundException) {
      super.isCleartextTrafficPermitted(hostname)
    } catch (e: NoSuchMethodException) {
      super.isCleartextTrafficPermitted(hostname)
    } catch (e: IllegalAccessException) {
      throw AssertionError("unable to determine cleartext support", e)
    } catch (e: IllegalArgumentException) {
      throw AssertionError("unable to determine cleartext support", e)
    } catch (e: InvocationTargetException) {
      throw AssertionError("unable to determine cleartext support", e)
    }
  }

  @Throws(InvocationTargetException::class, IllegalAccessException::class)
  private fun api24IsCleartextTrafficPermitted(
    hostname: String, networkPolicyClass: Class<*>,
    networkSecurityPolicy: Any
  ): Boolean = try {
    val isCleartextTrafficPermittedMethod = networkPolicyClass
        .getMethod("isCleartextTrafficPermitted", String::class.java)
    isCleartextTrafficPermittedMethod.invoke(networkSecurityPolicy, hostname) as Boolean
  } catch (e: NoSuchMethodException) {
    api23IsCleartextTrafficPermitted(hostname, networkPolicyClass, networkSecurityPolicy)
  }

  @Throws(InvocationTargetException::class, IllegalAccessException::class)
  private fun api23IsCleartextTrafficPermitted(
    hostname: String, networkPolicyClass: Class<*>,
    networkSecurityPolicy: Any
  ): Boolean = try {
    val isCleartextTrafficPermittedMethod = networkPolicyClass
        .getMethod("isCleartextTrafficPermitted")
    isCleartextTrafficPermittedMethod.invoke(networkSecurityPolicy) as Boolean
  } catch (e: NoSuchMethodException) {
    super.isCleartextTrafficPermitted(hostname)
  }

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      try {
        val extensionsClass = Class.forName("android.net.http.X509TrustManagerExtensions")
        val constructor = extensionsClass.getConstructor(X509TrustManager::class.java)
        val extensions = constructor.newInstance(trustManager)
        val checkServerTrusted = extensionsClass.getMethod(
            "checkServerTrusted", Array<X509Certificate>::class.java, String::class.java,
            String::class.java)
        AndroidCertificateChainCleaner(extensions, checkServerTrusted)
      } catch (e: Exception) {
        super.buildCertificateChainCleaner(trustManager)
      }

  override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex = try {
    // From org.conscrypt.TrustManagerImpl, we want the method with this signature:
    // private TrustAnchor findTrustAnchorByIssuerAndSignature(X509Certificate lastCert);
    val method = trustManager.javaClass.getDeclaredMethod(
        "findTrustAnchorByIssuerAndSignature", X509Certificate::class.java)
    method.isAccessible = true
    CustomTrustRootIndex(trustManager, method)
  } catch (e: NoSuchMethodException) {
    super.buildTrustRootIndex(trustManager)
  }

  /**
   * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
   * This is the best way to get a clean chain on Android because it uses the same code as the TLS
   * handshake.
   */
  internal class AndroidCertificateChainCleaner(
    private val x509TrustManagerExtensions: Any,
    private val checkServerTrusted: Method
  ) : CertificateChainCleaner() {

    @Suppress("UNCHECKED_CAST")
    @Throws(SSLPeerUnverifiedException::class)
    override// Reflection on List<Certificate>.
    fun clean(chain: List<Certificate>, hostname: String): List<Certificate> = try {
      val certificates = (chain as List<X509Certificate>).toTypedArray()
      checkServerTrusted.invoke(
          x509TrustManagerExtensions, certificates, "RSA", hostname) as List<Certificate>
    } catch (e: InvocationTargetException) {
      val exception = SSLPeerUnverifiedException(e.message)
      exception.initCause(e)
      throw exception
    } catch (e: IllegalAccessException) {
      throw AssertionError(e)
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidCertificateChainCleaner // All instances are equivalent.

    override fun hashCode(): Int = 0
  }

  /**
   * Provides access to the internal dalvik.system.CloseGuard class. Android uses this in
   * combination with android.os.StrictMode to report on leaked java.io.Closeable's. Available since
   * Android API 11.
   */
  internal class CloseGuard(
    private val getMethod: Method?,
    private val openMethod: Method?,
    private val warnIfOpenMethod: Method?
  ) {

    fun createAndOpen(closer: String): Any? {
      if (getMethod != null) {
        try {
          val closeGuardInstance = getMethod.invoke(null)
          openMethod!!.invoke(closeGuardInstance, closer)
          return closeGuardInstance
        } catch (ignored: Exception) {
        }
      }
      return null
    }

    fun warnIfOpen(closeGuardInstance: Any?): Boolean {
      var reported = false
      if (closeGuardInstance != null) {
        try {
          warnIfOpenMethod!!.invoke(closeGuardInstance)
          reported = true
        } catch (ignored: Exception) {
        }
      }
      return reported
    }

    companion object {
      fun get(): CloseGuard {
        var getMethod: Method?
        var openMethod: Method?
        var warnIfOpenMethod: Method?

        try {
          val closeGuardClass = Class.forName("dalvik.system.CloseGuard")
          getMethod = closeGuardClass.getMethod("get")
          openMethod = closeGuardClass.getMethod("open", String::class.java)
          warnIfOpenMethod = closeGuardClass.getMethod("warnIfOpen")
        } catch (ignored: Exception) {
          getMethod = null
          openMethod = null
          warnIfOpenMethod = null
        }

        return CloseGuard(getMethod, openMethod, warnIfOpenMethod)
      }
    }
  }

  /**
   * A trust manager for Android applications that customize the trust manager.
   *
   *
   * This class exploits knowledge of Android implementation details. This class is potentially
   * much faster to initialize than [BasicTrustRootIndex] because it doesn't need to load and
   * index trusted CA certificates.
   */
  internal data class CustomTrustRootIndex(
    private val trustManager: X509TrustManager,
    private val findByIssuerAndSignatureMethod: Method
  ) : TrustRootIndex {

    override fun findByIssuerAndSignature(cert: X509Certificate): X509Certificate? {
      return try {
        val trustAnchor = findByIssuerAndSignatureMethod.invoke(
            trustManager, cert) as TrustAnchor
        trustAnchor.trustedCert
      } catch (e: IllegalAccessException) {
        throw AssertionError("unable to get issues and signature", e)
      } catch (e: InvocationTargetException) {
        null
      }
    }
  }

  companion object {
    private const val MAX_LOG_LENGTH = 4000

    fun buildIfSupported(): Platform? {
      // Attempt to find Android 5+ APIs.
      val sslParametersClass: Class<*>
      val sslSocketClass: Class<*>
      try {
        sslParametersClass = Class.forName("com.android.org.conscrypt.SSLParametersImpl")
        sslSocketClass = Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl")
      } catch (ignored: ClassNotFoundException) {
        return null // Not an Android runtime.
      }

      if (Build.VERSION.SDK_INT >= 21) {
        try {
          val setUseSessionTickets = sslSocketClass.getDeclaredMethod(
              "setUseSessionTickets", Boolean::class.javaPrimitiveType)
          val setHostname = sslSocketClass.getMethod("setHostname", String::class.java)
          val getAlpnSelectedProtocol = sslSocketClass.getMethod("getAlpnSelectedProtocol")
          val setAlpnProtocols = sslSocketClass.getMethod("setAlpnProtocols", ByteArray::class.java)
          return AndroidPlatform(sslParametersClass, sslSocketClass, setUseSessionTickets,
              setHostname, getAlpnSelectedProtocol, setAlpnProtocols)
        } catch (ignored: NoSuchMethodException) {
        }
      }
      throw IllegalStateException(
          "Expected Android API level 21+ but was " + Build.VERSION.SDK_INT)
    }
  }
}
