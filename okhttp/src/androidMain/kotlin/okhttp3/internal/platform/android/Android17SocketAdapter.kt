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
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
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
class Android17SocketAdapter
@RequiresApi(36)
internal constructor() : SocketAdapter {
  init {
    println("AndroidCanarySocketAdapter")
  }

  override fun matchesSocket(sslSocket: SSLSocket): Boolean =
    SSLSockets.isSupportedSocket(sslSocket)

  override fun isSupported(): Boolean = Companion.isSupported()

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
  // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
    // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
    when (val protocol = sslSocket.applicationProtocol) {
      null, "" -> null
      else -> protocol
    }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
  ) {
    SSLSockets.setUseSessionTickets(sslSocket, true)

    val sslParameters = sslSocket.sslParameters

    // Enable ALPN.
    sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

// Would need access to Dns to do it here
//    println("setting ECH")
//    SSLSockets.setEchConfigList(
//      sslSocket,
//      EchConfigList.fromBytes(
//        echDevList.toByteArray()
//      )
//    )

    sslSocket.sslParameters = sslParameters
  }

  @SuppressSignatureCheck
  companion object {


    fun buildIfSupported(): SocketAdapter? =
      if (isSupported()) Android17SocketAdapter() else null

    @ChecksSdkIntAtLeast(api = 36)
    fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 36
  }
}
