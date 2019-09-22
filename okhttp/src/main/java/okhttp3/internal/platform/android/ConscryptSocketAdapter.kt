/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.platform.android

import okhttp3.Protocol
import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Platform
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Simple non-reflection SocketAdapter for Conscrypt.
 */
class ConscryptSocketAdapter : SocketAdapter {
  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean = false

  override fun matchesSocket(sslSocket: SSLSocket): Boolean = Conscrypt.isConscrypt(sslSocket)

  override fun isSupported(): Boolean = ConscryptPlatform.isSupported

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      when {
        matchesSocket(sslSocket) -> Conscrypt.getApplicationProtocol(sslSocket)
        else -> null // No TLS extensions if the socket class is custom.
      }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    protocols: List<Protocol>
  ) {
    // No TLS extensions if the socket class is custom.
    if (matchesSocket(sslSocket)) {
      // Enable session tickets.
      Conscrypt.setUseSessionTickets(sslSocket, true)

      // Enable ALPN.
      val names = Platform.alpnProtocolNames(protocols)
      Conscrypt.setApplicationProtocols(sslSocket, names.toTypedArray())
    }
  }

  companion object {
    fun buildIfSupported(): SocketAdapter? =
        if (ConscryptPlatform.isSupported) ConscryptSocketAdapter() else null
  }
}
