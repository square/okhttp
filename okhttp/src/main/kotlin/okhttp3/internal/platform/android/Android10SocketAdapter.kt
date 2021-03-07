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

import android.annotation.SuppressLint
import android.net.ssl.SSLSockets
import android.os.Build
import java.io.IOException
import java.lang.IllegalArgumentException
import javax.net.ssl.SSLSocket
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.isAndroid

/**
 * Simple non-reflection SocketAdapter for Android Q+.
 *
 * These API assumptions make it unsuitable for use on earlier Android versions.
 */
@SuppressLint("NewApi")
@SuppressSignatureCheck
class Android10SocketAdapter : SocketAdapter {
  override fun matchesSocket(sslSocket: SSLSocket): Boolean = SSLSockets.isSupportedSocket(sslSocket)

  override fun isSupported(): Boolean = Companion.isSupported()

  @SuppressLint("NewApi")
  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      when (val protocol = sslSocket.applicationProtocol) {
        null, "" -> null
        else -> protocol
      }

  @SuppressLint("NewApi")
  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  ) {
    try {
      SSLSockets.setUseSessionTickets(sslSocket, true)

      val sslParameters = sslSocket.sslParameters

      // Enable ALPN.
      sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

      sslSocket.sslParameters = sslParameters
    } catch (iae: IllegalArgumentException) {
      // probably java.lang.IllegalArgumentException: Invalid input to toASCII from IDN.toASCII
      throw IOException("Android internal error", iae)
    }
  }

  @SuppressSignatureCheck
  companion object {
    fun buildIfSupported(): SocketAdapter? =
        if (isSupported()) Android10SocketAdapter() else null

    fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 29
  }
}
