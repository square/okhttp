/*
 * Copyright (C) 2014 Square, Inc.
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

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import okhttp3.TestUtil.threadFactory
import okhttp3.internal.and
import okhttp3.internal.closeQuietly
import okhttp3.internal.threadName
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import okio.use

/**
 * A limited implementation of SOCKS Protocol Version 5, intended to be similar to MockWebServer.
 * See [RFC 1928](https://www.ietf.org/rfc/rfc1928.txt).
 */
class SocksProxy {
  private val executor = Executors.newCachedThreadPool(threadFactory("SocksProxy"))
  private var serverSocket: ServerSocket? = null
  private val connectionCount = AtomicInteger()
  private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

  fun play() {
    serverSocket = ServerSocket(0)
    executor.execute {
      val threadName = "SocksProxy ${serverSocket!!.localPort}"
      Thread.currentThread().name = threadName
      try {
        while (true) {
          val socket = serverSocket!!.accept()
          connectionCount.incrementAndGet()
          service(socket)
        }
      } catch (e: SocketException) {
        logger.info("$threadName done accepting connections: ${e.message}")
      } catch (e: IOException) {
        logger.log(Level.WARNING, "$threadName failed unexpectedly", e)
      } finally {
        for (socket in openSockets) {
          socket.closeQuietly()
        }
        Thread.currentThread().name = "SocksProxy"
      }
    }
  }

  fun proxy(): Proxy =
    Proxy(
      Proxy.Type.SOCKS,
      InetSocketAddress.createUnresolved("localhost", serverSocket!!.localPort),
    )

  fun connectionCount(): Int = connectionCount.get()

  fun shutdown() {
    serverSocket!!.close()
    executor.shutdown()
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      throw IOException("Gave up waiting for executor to shut down")
    }
  }

  private fun service(from: Socket) {
    val name = "SocksProxy ${from.remoteSocketAddress}"
    threadName(name) {
      try {
        val fromSource = from.source().buffer()
        val fromSink = from.sink().buffer()
        hello(fromSource, fromSink)
        acceptCommand(from.inetAddress, fromSource, fromSink)
        openSockets.add(from)
      } catch (e: IOException) {
        logger.log(Level.WARNING, "$name failed", e)
        from.closeQuietly()
      }
    }
  }

  private fun hello(
    fromSource: BufferedSource,
    fromSink: BufferedSink,
  ) {
    val version = fromSource.readByte() and 0xff
    val methodCount = fromSource.readByte() and 0xff
    var selectedMethod = METHOD_NONE
    if (version != VERSION_5) {
      throw ProtocolException("unsupported version: $version")
    }
    for (i in 0 until methodCount) {
      val candidateMethod: Int = fromSource.readByte() and 0xff
      if (candidateMethod == METHOD_NO_AUTHENTICATION_REQUIRED) {
        selectedMethod = candidateMethod
      }
    }
    when (selectedMethod) {
      METHOD_NO_AUTHENTICATION_REQUIRED -> {
        fromSink.writeByte(VERSION_5)
        fromSink.writeByte(selectedMethod)
        fromSink.emit()
      }
      else -> throw ProtocolException("unsupported method: $selectedMethod")
    }
  }

  private fun acceptCommand(
    fromAddress: InetAddress,
    fromSource: BufferedSource,
    fromSink: BufferedSink,
  ) {
    // Read the command.
    val version = fromSource.readByte() and 0xff
    if (version != VERSION_5) throw ProtocolException("unexpected version: $version")

    val command = fromSource.readByte() and 0xff

    val reserved = fromSource.readByte() and 0xff
    if (reserved != 0) throw ProtocolException("unexpected reserved: $reserved")

    val addressType = fromSource.readByte() and 0xff
    val toAddress =
      when (addressType) {
        ADDRESS_TYPE_IPV4 -> {
          InetAddress.getByAddress(fromSource.readByteArray(4L))
        }

        ADDRESS_TYPE_DOMAIN_NAME -> {
          val domainNameLength: Int = fromSource.readByte() and 0xff
          val domainName = fromSource.readUtf8(domainNameLength.toLong())
          // Resolve HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS to localhost.
          when {
            domainName.equals(HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS, ignoreCase = true) -> {
              InetAddress.getByName("localhost")
            }
            else -> InetAddress.getByName(domainName)
          }
        }

        else -> throw ProtocolException("unsupported address type: $addressType")
      }

    val port = fromSource.readShort() and 0xffff

    when (command) {
      COMMAND_CONNECT -> {
        val toSocket = Socket(toAddress, port)
        val localAddress = toSocket.localAddress.address
        if (localAddress.size != 4) {
          throw ProtocolException("unexpected address: " + toSocket.localAddress)
        }

        // Write the reply.
        fromSink.writeByte(VERSION_5)
        fromSink.writeByte(REPLY_SUCCEEDED)
        fromSink.writeByte(0)
        fromSink.writeByte(ADDRESS_TYPE_IPV4)
        fromSink.write(localAddress)
        fromSink.writeShort(toSocket.localPort)
        fromSink.emit()
        logger.log(Level.INFO, "SocksProxy connected $fromAddress to $toAddress")

        // Copy sources to sinks in both directions.
        val toSource = toSocket.source().buffer()
        val toSink = toSocket.sink().buffer()
        openSockets.add(toSocket)
        transfer(fromAddress, toAddress, fromSource, toSink)
        transfer(fromAddress, toAddress, toSource, fromSink)
      }

      else -> throw ProtocolException("unexpected command: $command")
    }
  }

  private fun transfer(
    fromAddress: InetAddress,
    toAddress: InetAddress,
    source: BufferedSource,
    sink: BufferedSink,
  ) {
    executor.execute {
      val name = "SocksProxy $fromAddress to $toAddress"
      threadName(name) {
        val buffer = Buffer()
        try {
          sink.use {
            source.use {
              while (true) {
                val byteCount = source.read(buffer, 8192L)
                if (byteCount == -1L) break
                sink.write(buffer, byteCount)
                sink.emit()
              }
            }
          }
        } catch (e: IOException) {
          logger.log(Level.WARNING, "$name failed", e)
        }
      }
    }
  }

  companion object {
    const val HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS = "onlyProxyCanResolveMe.org"
    private const val VERSION_5 = 5
    private const val METHOD_NONE = 0xff
    private const val METHOD_NO_AUTHENTICATION_REQUIRED = 0
    private const val ADDRESS_TYPE_IPV4 = 1
    private const val ADDRESS_TYPE_DOMAIN_NAME = 3
    private const val COMMAND_CONNECT = 1
    private const val REPLY_SUCCEEDED = 0
    private val logger = Logger.getLogger(SocksProxy::class.java.name)
  }
}
