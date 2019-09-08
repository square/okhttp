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

import okhttp3.internal.futureapi.android.net.http.isCleartextTrafficPermittedX
import okhttp3.internal.futureapi.android.os.BuildX
import okhttp3.internal.futureapi.android.util.isAndroid
import okhttp3.internal.platform.android.AndroidQCertificateChainCleaner
import okhttp3.internal.tls.CertificateChainCleaner
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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
      isCleartextTrafficPermittedX(hostname)

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      AndroidQCertificateChainCleaner(trustManager)

  companion object {
    val isSupported: Boolean = isAndroid && BuildX.VERSION_SDK_INT >= 29

    fun buildIfSupported(): Platform? = if (isSupported) AndroidQPlatform() else null
  }
}
