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

import android.net.http.X509TrustManagerExtensions
import android.os.Build
import android.security.NetworkSecurityPolicy
import okhttp3.internal.tls.CertificateChainCleaner
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/** Android 29+. */
class AndroidQPlatform : Jdk9Platform() {
  @Throws(IOException::class)
  override fun connectSocket(
    socket: Socket,
    address: InetSocketAddress,
    connectTimeout: Int
  ) {
      socket.connect(address, connectTimeout)
  }

  override fun isCleartextTrafficPermitted(hostname: String): Boolean =
      NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      AndroidQCertificateChainCleaner(trustManager)

  /**
   * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
   * This is the best way to get a clean chain on Android because it uses the same code as the TLS
   * handshake.
   */
  internal class AndroidQCertificateChainCleaner(
    trustManager: X509TrustManager
  ) : CertificateChainCleaner() {
    val extensions = X509TrustManagerExtensions(trustManager)

    @Suppress("UNCHECKED_CAST")
    @Throws(SSLPeerUnverifiedException::class)
    override // Reflection on List<Certificate>.
    fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
      val certificates = (chain as List<X509Certificate>).toTypedArray()
      return extensions.checkServerTrusted(certificates, "RSA", hostname)
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidQCertificateChainCleaner // All instances are equivalent.

    override fun hashCode(): Int = 1
  }

  companion object {
    val isSupported: Boolean = try {
      // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
      Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl")

      Build.VERSION.SDK_INT >= 29
    } catch (_: ClassNotFoundException) {
      false
    }

    fun buildIfSupported(): Platform? = if (isSupported) AndroidQPlatform() else null
  }
}
