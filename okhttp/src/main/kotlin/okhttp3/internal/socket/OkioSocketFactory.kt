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

import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

interface OkioSocketFactory {
  fun createSocket(): OkioSocket

  fun createSocket(proxy: Proxy): OkioSocket

  fun createSocket(
    host: String,
    port: Int,
  ): OkioSocket
}

class RealOkioSocketFactory(
  internal val delegate: SocketFactory = SocketFactory.getDefault(),
) : OkioSocketFactory {
  override fun createSocket() = RealOkioSocket(delegate.createSocket() ?: Socket())

  override fun createSocket(
    host: String,
    port: Int,
  ): OkioSocket = RealOkioSocket(delegate.createSocket(host, port) ?: Socket(host, port))

  override fun createSocket(proxy: Proxy) = RealOkioSocket(Socket(proxy)) // Don't delegate.
}
