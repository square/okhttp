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
import android.security.NetworkSecurityPolicy
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.android.AndroidCertificateChainCleaner
import okhttp3.internal.platform.android.AndroidSocketAdapter
import okhttp3.internal.platform.android.BouncyCastleSocketAdapter
import okhttp3.internal.platform.android.CloseGuard
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.platform.android.StandardAndroidSocketAdapter
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex

/** Android 5+. */
@SuppressSignatureCheck
class AndroidPlatform : Platform() {
  private val socketAdapters = listOfNotNull(
      StandardAndroidSocketAdapter.buildIfSupported(),
      DeferredSocketAdapter(AndroidSocketAdapter.playProviderFactory),
      // Delay and Defer any initialisation of Conscrypt and BouncyCastle
      DeferredSocketAdapter(ConscryptSocketAdapter.factory),
      DeferredSocketAdapter(BouncyCastleSocketAdapter.factory)
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
    protocols: List<@JvmSuppressWildcards Protocol>
  ) {
    // No TLS extensions if the socket class is custom.
    socketAdapters.find { it.matchesSocket(sslSocket) }
        ?.configureTlsExtensions(sslSocket, hostname, protocols)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket) =
      // No TLS extensions if the socket class is custom.
      socketAdapters.find { it.matchesSocket(sslSocket) }?.getSelectedProtocol(sslSocket)

  override fun getStackTraceForCloseable(closer: String): Any? = closeGuard.createAndOpen(closer)

  override fun logCloseableLeak(message: String, stackTrace: Any?) {
    val reported = closeGuard.warnIfOpen(stackTrace)
    if (!reported) {
      // Unable to report via CloseGuard. As a last-ditch effort, send it to the logger.
      log(message, WARN)
    }
  }

  override fun isCleartextTrafficPermitted(hostname: String): Boolean = when {
    Build.VERSION.SDK_INT >= 24 -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)
    Build.VERSION.SDK_INT >= 23 -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
    else -> true
  }

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
        AndroidCertificateChainCleaner.buildIfSupported(trustManager) ?: super.buildCertificateChainCleaner(trustManager)

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
    val isSupported: Boolean = when {
      !isAndroid -> false
      Build.VERSION.SDK_INT >= 30 -> false // graylisted methods are banned
      else -> {
        // Fail Fast
        check(
            Build.VERSION.SDK_INT >= 21) { "Expected Android API level 21+ but was ${Build.VERSION.SDK_INT}" }

        true
      }
    }

    fun buildIfSupported(): Platform? = if (isSupported) AndroidPlatform() else null
  }
}
