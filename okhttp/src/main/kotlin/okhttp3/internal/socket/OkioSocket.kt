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

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import okhttp3.internal.peerName
import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable
import okio.buffer
import okio.sink
import okio.source

interface OkioSocket : Closeable {
  val source: BufferedSource
  val sink: BufferedSink

  var soTimeout: Int
  val isClosed: Boolean
  val isInputShutdown: Boolean
  val isOutputShutdown: Boolean
  val peerName: String
  val localPort: Int
  val inetAddress: InetAddress?
  val localAddress: InetAddress
  val remoteSocketAddress: SocketAddress?

  fun connect(address: InetSocketAddress)

  fun connect(
    address: InetSocketAddress,
    connectTimeout: Int,
  )

  fun shutdownOutput()

  fun shutdownInput()
}

class RealOkioSocket(
  val delegate: Socket,
) : OkioSocket {
  private var _source: BufferedSource? = null
  private var _sink: BufferedSink? = null

  override val source: BufferedSource
    get() = _source ?: delegate.source().buffer().also { _source = it }
  override val sink: BufferedSink
    get() = _sink ?: delegate.sink().buffer().also { _sink = it }

  override val localPort: Int by delegate::localPort
  override val inetAddress: InetAddress? by delegate::inetAddress
  override val localAddress: InetAddress by delegate::localAddress
  override val remoteSocketAddress: SocketAddress? by delegate::remoteSocketAddress
  override var soTimeout: Int by delegate::soTimeout

  override val peerName: String get() = delegate.peerName()
  override val isClosed: Boolean get() = delegate.isClosed
  override val isInputShutdown: Boolean get() = delegate.isInputShutdown
  override val isOutputShutdown: Boolean get() = delegate.isOutputShutdown

  override fun connect(address: InetSocketAddress) {
    delegate.connect(address)
  }

  override fun connect(
    address: InetSocketAddress,
    connectTimeout: Int,
  ) {
    delegate.connect(address, connectTimeout)
  }

  override fun shutdownOutput() {
    delegate.shutdownOutput()
  }

  override fun shutdownInput() {
    delegate.shutdownInput()
  }

  override fun close() {
    // Note that this potentially leaves bytes in sink. This is necessary because Socket.close() is
    // much more like cancel() (asynchronously interrupt) than close() (release resources).
    delegate.close()
  }
}
