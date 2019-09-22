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

import android.net.SSLCertificateSocketFactory
import android.os.Build
import okhttp3.Protocol
import okhttp3.internal.platform.AndroidPlatform.Companion.isAndroid
import okhttp3.internal.platform.Platform
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Simple non-reflection SocketAdapter for Android Q.
 */
class Android10SocketAdapter : SocketAdapter {
  private val socketFactory =
      SSLCertificateSocketFactory.getDefault(10000) as SSLCertificateSocketFactory

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean = false

  override fun matchesSocket(sslSocket: SSLSocket): Boolean = sslSocket.javaClass.name.startsWith(
      "com.android.org.conscrypt")

  override fun isSupported(): Boolean = Companion.isSupported()

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      when (val protocol = sslSocket.applicationProtocol) {
        null, "" -> null
        else -> protocol
      }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    protocols: List<Protocol>
  ) {
    socketFactory.setUseSessionTickets(sslSocket, true)

    val sslParameters = sslSocket.sslParameters

    // Enable ALPN.
    sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

    sslSocket.sslParameters = sslParameters
  }

  companion object {
    fun buildIfSupported(): SocketAdapter? =
        if (isSupported()) Android10SocketAdapter() else null

    fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 29
  }
}
