/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.android

import android.net.Network
import android.os.Build
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.ExperimentalOkHttpApi

@ExperimentalOkHttpApi
class AndroidSocketFactory(
  val network: Network,
) : SocketFactory() {
  private val socketFactory: SocketFactory = network.socketFactory

  override fun createSocket(): Socket {
    return socketFactory.createSocket()
  }

  override fun createSocket(
    host: String?,
    port: Int,
  ): Socket {
    return socketFactory.createSocket(host, port)
  }

  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket {
    return socketFactory.createSocket(host, port, localHost, localPort)
  }

  override fun createSocket(
    host: InetAddress?,
    port: Int,
  ): Socket {
    return socketFactory.createSocket(host, port)
  }

  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int,
  ): Socket {
    return socketFactory.createSocket(address, port, localAddress, localPort)
  }

  override fun hashCode(): Int {
    return if (Build.VERSION.SDK_INT >= 23) {
      network.networkHandle.hashCode()
    } else {
      network.toString().hashCode()
    }
  }

  override fun equals(other: Any?): Boolean {
    return other is AndroidSocketFactory &&
      network == other.network
  }

  override fun toString(): String {
    return "AndroidSocketFactory{$network}"
  }
}
