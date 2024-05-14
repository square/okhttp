/*
 * Copyright (C) 2024 Square, Inc.
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

package okhttp3.internal.socket

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

interface OkioSslSocketFactory {
  fun createSocket(socket: OkioSocket): OkioSslSocket

  fun createSocket(
    socket: OkioSocket,
    host: String,
    port: Int,
  ): OkioSslSocket
}

class RealOkioSslSocketFactory(
  val delegate: SSLSocketFactory,
) : OkioSslSocketFactory {
  override fun createSocket(socket: OkioSocket): OkioSslSocket {
    val delegateSocket = (socket as RealOkioSocket).delegate
    return createSocket(
      socket,
      delegateSocket.inetAddress.hostAddress,
      delegateSocket.port,
    )
  }

  override fun createSocket(
    socket: OkioSocket,
    host: String,
    port: Int,
  ): OkioSslSocket {
    val delegateSocket = (socket as RealOkioSocket).delegate
    val sslSocket = delegate.createSocket(delegateSocket, host, port, true) as SSLSocket
    return RealOkioSslSocket(sslSocket)
  }
}
