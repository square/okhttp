/*
 * Copyright (C) 2026 Block, Inc.
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source

/** A [Socket] implementation that delegates to another [Socket] and records events. */
public open class RecordingSocket(
  delegate: Socket,
  private val socketEventListener: SocketEventListener,
  public val socketName: String = "Socket"
) : SocketDecorator(delegate) {
  init {
    if (delegate.isConnected) {
      recordSocketConnection()
      recordConnect(socketConnection.peer)
    }
  }

  private val clock = Clock.System
  private val lock = ReentrantLock()

  private lateinit var socketConnection: SocketEvent.SocketConnection

  override fun connect(endpoint: SocketAddress?) {
    super.connect(endpoint)
    recordSocketConnection()
    recordConnect(endpoint)
  }

  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    super.connect(endpoint, timeout)
    recordSocketConnection()
    recordConnect(endpoint)
  }

  private fun recordSocketConnection() {
    this.socketConnection = SocketEvent.SocketConnection(
      delegate.localSocketAddress as InetSocketAddress,
      delegate.remoteSocketAddress as InetSocketAddress
    )
  }

  private val mySource: Source by lazy {
    object : ForwardingSource(delegate.source()) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        val startSize = sink.size
        val readCount = super.read(sink, byteCount)

        val payloadSize = sink.size - startSize
        val payload = if (payloadSize > 0) {
          val clone = Buffer()
          sink.copyTo(clone, startSize, payloadSize)
          clone
        } else null

        val event = if (readCount == -1L) {
          SocketEvent.ReadEof(
            clock.now(), Thread.currentThread().name, socketName,
            socketConnection,
          )
        } else {
          SocketEvent.ReadSuccess(
            clock.now(),
            Thread.currentThread().name,
            socketName,
            socketConnection,
            readCount,
            payload,
          )
        }
        socketEventListener.onEvent(event)
        return readCount
      }

      override fun close() {
        super.close()
        socketEventListener.onEvent(
          SocketEvent.ShutdownInput(
            clock.now(),
            Thread.currentThread().name,
            socketName,
            socketConnection,
          )
        )
      }
    }
  }

  private val mySink: Sink by lazy {
    object : ForwardingSink(delegate.sink()) {
      override fun write(source: Buffer, byteCount: Long) {
        val payload = if (byteCount > 0) {
          val clone = Buffer()
          source.copyTo(clone, 0, byteCount)
          clone
        } else null

        super.write(source, byteCount)

        socketEventListener.onEvent(
          SocketEvent.WriteSuccess(
            clock.now(),
            Thread.currentThread().name,
            socketName,
            socketConnection,
            byteCount,
            clock.now(),
            payload
          )
        )
      }

      override fun close() {
        super.close()
        socketEventListener.onEvent(
          SocketEvent.ShutdownOutput(
            clock.now(),
            Thread.currentThread().name,
            socketName,
            socketConnection,
          )
        )
      }
    }
  }

  private val myInputStream by lazy { mySource.buffer().inputStream() }
  private val myOutputStream by lazy { mySink.buffer().outputStream() }

  private fun recordConnect(endpoint: SocketAddress?) {
    val address = endpoint as? java.net.InetSocketAddress
    socketEventListener.onEvent(
      SocketEvent.Connect(
        clock.now(),
        Thread.currentThread().name,
        socketName,
        socketConnection,
        address?.hostName,
        address?.port ?: 0
      )
    )
  }

  override fun getInputStream(): InputStream = myInputStream

  override fun getOutputStream(): OutputStream = myOutputStream

  override fun close() {
    delegate.close()
    if (this::socketConnection.isInitialized) {
      socketEventListener.onEvent(
        SocketEvent.Close(
          clock.now(), Thread.currentThread().name, socketName,
          socketConnection,
        )
      )
    }
  }

  override fun shutdownInput() {
    delegate.shutdownInput()
    socketEventListener.onEvent(
      SocketEvent.ShutdownInput(
        clock.now(),
        Thread.currentThread().name,
        socketName,
        socketConnection,
      )
    )
  }

  override fun shutdownOutput() {
    delegate.shutdownOutput()
    lock.withLock {
      socketEventListener.onEvent(
        SocketEvent.ShutdownOutput(
          clock.now(),
          Thread.currentThread().name,
          socketName,
          socketConnection,
        )
      )
    }
  }
}

