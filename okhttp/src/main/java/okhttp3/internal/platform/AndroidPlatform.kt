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
import okhttp3.Protocol
import okhttp3.internal.platform.android.CloseGuard
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.platform.android.StandardAndroidSocketAdapter
import okhttp3.internal.platform.android.androidLog
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Android 5+. */
class AndroidPlatform : Platform() {
  private val socketAdapters = listOfNotNull(
      StandardAndroidSocketAdapter.buildIfSupported(),
      ConscryptSocketAdapter.buildIfSupported(),
      DeferredSocketAdapter("com.google.android.gms.org.conscrypt")
  ).filter { it.isSupported() }

  private val closeGuard = CloseGuard.get()

  @Throws(IOException::class)
  override fun connectSocket(
    socket: Socket,
    address: InetSocketAddress,
    connectTimeout: Int
  ) {
    try {
      socket.connect(address, connectTimeout)
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

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
      socketAdapters.find { it.matchesSocketFactory(sslSocketFactory) }
          ?.trustManager(sslSocketFactory)

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  ) {
    // No TLS extensions if the socket class is custom.
    socketAdapters.find { it.matchesSocket(sslSocket) }
        ?.configureTlsExtensions(sslSocket, hostname, protocols)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket) =
      // No TLS extensions if the socket class is custom.
      socketAdapters.find { it.matchesSocket(sslSocket) }?.getSelectedProtocol(sslSocket)

  override fun log(level: Int, message: String, t: Throwable?) {
    androidLog(level, message, t)
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
    } catch (_: ClassNotFoundException) {
      super.isCleartextTrafficPermitted(hostname)
    } catch (_: NoSuchMethodException) {
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
    hostname: String,
    networkPolicyClass: Class<*>,
    networkSecurityPolicy: Any
  ): Boolean = try {
    val isCleartextTrafficPermittedMethod = networkPolicyClass
        .getMethod("isCleartextTrafficPermitted", String::class.java)
    isCleartextTrafficPermittedMethod.invoke(networkSecurityPolicy, hostname) as Boolean
  } catch (_: NoSuchMethodException) {
    api23IsCleartextTrafficPermitted(hostname, networkPolicyClass, networkSecurityPolicy)
  }

  @Throws(InvocationTargetException::class, IllegalAccessException::class)
  private fun api23IsCleartextTrafficPermitted(
    hostname: String,
    networkPolicyClass: Class<*>,
    networkSecurityPolicy: Any
  ): Boolean = try {
    val isCleartextTrafficPermittedMethod = networkPolicyClass
        .getMethod("isCleartextTrafficPermitted")
    isCleartextTrafficPermittedMethod.invoke(networkSecurityPolicy) as Boolean
  } catch (_: NoSuchMethodException) {
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
      } catch (_: Exception) {
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
    override // Reflection on List<Certificate>.
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
   * A trust manager for Android applications that customize the trust manager.
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
      } catch (_: InvocationTargetException) {
        null
      }
    }
  }

  companion object {
    val isSupported: Boolean = try {
      // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
      Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl")

      // Fail Fast
      check(Build.VERSION.SDK_INT >= 21) { "Expected Android API level 21+ but was ${Build.VERSION.SDK_INT}" }

      true
    } catch (_: ClassNotFoundException) {
      false
    }

    fun buildIfSupported(): Platform? = if (isSupported) AndroidPlatform() else null
  }
}
