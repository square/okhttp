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
import android.net.ssl.EchConfigList
import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLSocket
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okio.ByteString.Companion.decodeHex

/**
 * Simple non-reflection SocketAdapter for Android Q+.
 *
 * These API assumptions make it unsuitable for use on earlier Android versions.
 */
@SuppressLint("NewApi")
@SuppressSignatureCheck
class AndroidCanarySocketAdapter
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

    if (hostname == "cloudflare-ech.com") {
      println("setting ECH")
      SSLSockets.setEchConfigList(
        sslSocket,
        EchConfigList.fromBytes(cloudflareEchList.toByteArray())
      )
    } else if (hostname == "crypto.cloudflare.com") {
      println("setting ECH")
      SSLSockets.setEchConfigList(
        sslSocket,
        EchConfigList.fromBytes(
          cryptoCloudflareEchList.toByteArray()
        )
      )
    } else if (hostname == "tls-ech.dev") {
      println("setting ECH")
      SSLSockets.setEchConfigList(
        sslSocket,
        EchConfigList.fromBytes(
          echDevList.toByteArray()
        )
      )
    }

    sslSocket.sslParameters = sslParameters
  }

  @SuppressSignatureCheck
  companion object {
    val cloudflareEchList =
      "0045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d6563682e636f6d0000".decodeHex()

    val cryptoCloudflareEchList =
      "00 45 fe 0d 00 41 7f 00 20 00 20 58 40 10 23 63 d4 2a f1 76 3c 2e e1 87 fc de 2e 4f 8e d2 dd ff f6 f6 bb 5e c4 cf 04 a9 67 a1 4f 00 04 00 01 00 01 00 12 63 6c 6f 75 64 66 6c 61 72 65 2d 65 63 68 2e 63 6f 6d 00 00".replace(
        " ",
        ""
      ).decodeHex()

    val echDevList =
      "00 49 fe 0d 00 45 2b 00 20 00 20 01 58 81 d4 1a 3e 2e f8 f2 20 81 85 dc 47 92 45 d2 06 24 dd d0 91 8a 80 56 f2 e2 6a f4 7e 26 28 00 08 00 01 00 01 00 01 00 03 40 12 70 75 62 6c 69 63 2e 74 6c 73 2d 65 63 68 2e 64 65 76 00 00".replace(
        " ",
        ""
      ).decodeHex()


    fun buildIfSupported(): SocketAdapter? =
      if (isSupported()) AndroidCanarySocketAdapter() else null

    @ChecksSdkIntAtLeast(api = 36)
    fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 36
  }
}
