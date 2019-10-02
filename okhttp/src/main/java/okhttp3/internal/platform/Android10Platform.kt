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
import okhttp3.Protocol
import okhttp3.internal.platform.AndroidPlatform.Companion.isAndroid
import okhttp3.internal.platform.android.Android10CertificateChainCleaner
import okhttp3.internal.platform.android.Android10SocketAdapter
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.platform.android.androidLog
import okhttp3.internal.tls.CertificateChainCleaner
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Android 29+. */
class Android10Platform : Platform() {
  private val socketAdapters = listOfNotNull(
      Android10SocketAdapter.buildIfSupported(),
      ConscryptSocketAdapter.buildIfSupported(),
      DeferredSocketAdapter("com.google.android.gms.org.conscrypt")
  ).filter { it.isSupported() }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
      socketAdapters.find { it.matchesSocketFactory(sslSocketFactory) }
          ?.trustManager(sslSocketFactory)

  override fun configureTlsExtensions(sslSocket: SSLSocket, protocols: List<Protocol>) {
    // No TLS extensions if the socket class is custom.
    socketAdapters.find { it.matchesSocket(sslSocket) }
        ?.configureTlsExtensions(sslSocket, protocols)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket) =
      // No TLS extensions if the socket class is custom.
      socketAdapters.find { it.matchesSocket(sslSocket) }?.getSelectedProtocol(sslSocket)

  override fun log(message: String, level: Int, t: Throwable?) {
    androidLog(level, message, t)
  }

  override fun isCleartextTrafficPermitted(hostname: String): Boolean =
      NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      Android10CertificateChainCleaner(trustManager)

  companion object {
    val isSupported: Boolean = isAndroid && Build.VERSION.SDK_INT >= 29

    fun buildIfSupported(): Platform? = if (isSupported) Android10Platform() else null
  }
}
