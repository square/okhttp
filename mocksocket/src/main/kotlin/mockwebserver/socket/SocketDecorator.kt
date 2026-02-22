/*
 * Copyright (c) 2026 Block, Inc.
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
@file:OptIn(ExperimentalTime::class)

package mockwebserver.socket

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source

/**
 * Wraps a standard java.net.Socket with Okio sources and sinks that
 * emit SocketEvents to a provided listener. This Allows intercepting OkHttp's actual calls.
 */
public open class SocketDecorator(
  public val delegate: Socket,
) : Socket() {
  override fun connect(endpoint: SocketAddress?) {
    delegate.connect(endpoint)
  }

  override fun connect(
    endpoint: SocketAddress?,
    timeout: Int,
  ) {
    delegate.connect(endpoint, timeout)
  }

  override fun bind(bindpoint: SocketAddress?) {
    delegate.bind(bindpoint)
  }

  override fun getInetAddress(): InetAddress? = delegate.inetAddress

  override fun getLocalAddress(): InetAddress? = delegate.localAddress

  override fun getPort(): Int = delegate.port

  override fun getLocalPort(): Int = delegate.localPort

  override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

  override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

  override fun getChannel(): java.nio.channels.SocketChannel? = delegate.channel

  override fun getInputStream(): InputStream = delegate.getInputStream()

  override fun getOutputStream(): OutputStream = delegate.getOutputStream()

  override fun close() {
    delegate.close()
  }

  override fun setTcpNoDelay(on: Boolean) {
    delegate.tcpNoDelay = on
  }

  override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

  override fun setSoLinger(
    on: Boolean,
    linger: Int,
  ) {
    delegate.setSoLinger(on, linger)
  }

  override fun getSoLinger(): Int = delegate.soLinger

  override fun sendUrgentData(data: Int) {
    delegate.sendUrgentData(data)
  }

  override fun setOOBInline(on: Boolean) {
    delegate.oobInline = on
  }

  override fun getOOBInline(): Boolean = delegate.oobInline

  override fun setSoTimeout(timeout: Int) {
    delegate.soTimeout = timeout
  }

  override fun getSoTimeout(): Int = delegate.soTimeout

  override fun setSendBufferSize(size: Int) {
    delegate.sendBufferSize = size
  }

  override fun getSendBufferSize(): Int = delegate.sendBufferSize

  override fun setReceiveBufferSize(size: Int) {
    delegate.receiveBufferSize = size
  }

  override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

  override fun setKeepAlive(on: Boolean) {
    delegate.keepAlive = on
  }

  override fun getKeepAlive(): Boolean = delegate.keepAlive

  override fun setTrafficClass(tc: Int) {
    delegate.trafficClass = tc
  }

  override fun getTrafficClass(): Int = delegate.trafficClass

  override fun setReuseAddress(on: Boolean) {
    delegate.reuseAddress = on
  }

  override fun getReuseAddress(): Boolean = delegate.reuseAddress

  override fun shutdownInput() {
    delegate.shutdownInput()
  }

  override fun shutdownOutput() {
    delegate.shutdownOutput()
  }

  override fun toString(): String = delegate.toString()

  override fun isConnected(): Boolean = delegate.isConnected

  override fun isBound(): Boolean = delegate.isBound

  override fun isClosed(): Boolean = delegate.isClosed

  override fun isInputShutdown(): Boolean = delegate.isInputShutdown

  override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}
