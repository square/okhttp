/*
 * Copyright (C) 2021 Square, Inc.
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
package okhttp3

import java.net.InetAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

class ChannelSocketFactory : SocketFactory() {
  override fun createSocket(): Socket {
    return SocketChannel.open().socket()
  }

  override fun createSocket(host: String, port: Int): Socket = TODO("Not yet implemented")

  override fun createSocket(
    host: String,
    port: Int,
    localHost: InetAddress,
    localPort: Int
  ): Socket = TODO("Not yet implemented")

  override fun createSocket(host: InetAddress, port: Int): Socket =
    TODO("Not yet implemented")

  override fun createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = TODO("Not yet implemented")
}