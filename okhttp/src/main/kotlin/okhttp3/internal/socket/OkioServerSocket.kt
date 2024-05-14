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

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import okio.Closeable

interface OkioServerSocket : Closeable {
  val localPort: Int
  var reuseAddress: Boolean
  val localSocketAddress: SocketAddress?

  fun accept(): OkioSocket

  fun bind(
    socketAddress: SocketAddress,
    port: Int,
  )
}

class RealOkioServerSocket(
  private val delegate: ServerSocket,
) : OkioServerSocket {
  override val localPort by delegate::localPort
  override var reuseAddress by delegate::reuseAddress
  override val localSocketAddress: InetSocketAddress? get() = delegate.localSocketAddress as? InetSocketAddress

  override fun accept(): OkioSocket {
    return RealOkioSocket(delegate.accept())
  }

  override fun bind(
    socketAddress: SocketAddress,
    port: Int,
  ) {
    delegate.bind(socketAddress, port)
  }

  override fun close() {
    delegate.close()
  }
}
