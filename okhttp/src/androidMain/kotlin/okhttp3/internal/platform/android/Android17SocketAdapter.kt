/*
 * Copyright (c) 2026 OkHttp Authors
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
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.IOException
import javax.net.ssl.SSLSocket
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.connection.RealCall
import okhttp3.internal.platform.Android17Platform
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.isAndroid

/**
 * Socket adapter for Android 17+ platform TLS APIs.
 *
 * Unlike the older Android socket adapters, this calls public platform APIs directly instead of
 * using reflection or Conscrypt-specific hooks. It configures session tickets, ALPN, and ECH on
 * Android's `SSLSocket` implementation.
 *
 * These API assumptions make it unsuitable for earlier Android versions; use
 * [Android17Platform] to select this adapter only when the runtime SDK supports it.
 */
@SuppressLint("NewApi")
@SuppressSignatureCheck
class Android17SocketAdapter
  @RequiresApi(36)
  internal constructor() : SocketAdapter {
    override fun matchesSocket(sslSocket: SSLSocket): Boolean = SSLSockets.isSupportedSocket(sslSocket)

    override fun isSupported(): Boolean = Companion.isSupported()

    override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
      // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
      when (val protocol = sslSocket.applicationProtocol) {
        null, "" -> null
        else -> protocol
      }

    override fun configureTlsExtensions(
      call: Call?,
      sslSocket: SSLSocket,
      hostname: String?,
      protocols: List<Protocol>,
    ) {
      try {
        SSLSockets.setUseSessionTickets(sslSocket, true)

        val sslParameters = sslSocket.sslParameters

        // Enable ALPN.
        sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

        sslSocket.sslParameters = sslParameters

        if (hostname != null) {
          val realCall = call as? RealCall ?: return
          val client = realCall.client

          val echModeConfiguration = client.echModeConfiguration

          val echMode =
            realCall.echMode
              ?: echModeConfiguration.echMode(hostname).also { realCall.echMode = it }

          if (echMode.attempt) {
            // echConfig was resolved during DNS (RealCall.resolveAddresses); just apply it here.
            echModeConfiguration.applyEch(sslSocket, echMode, hostname, realCall.echConfig)
          }
        }
      } catch (iae: IllegalArgumentException) {
        // Conscrypt's getSSLParameters() eagerly builds an SNIHostName from the peer host and
        // throws "Invalid input to toASCII" (via IDN.toASCII) for names that violate STD3 ASCII,
        // such as hostnames containing underscores. The JDK skips such names instead of throwing;
        // wrap it as an IOException so the call fails cleanly. Mirrors Android10SocketAdapter.
        throw IOException("Android internal error", iae)
      }
    }

    @SuppressSignatureCheck
    companion object {
      fun buildIfSupported(): SocketAdapter? = if (isSupported()) Android17SocketAdapter() else null

      @ChecksSdkIntAtLeast(api = 36)
      fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 36
    }
  }
