/*
 * Copyright (C) 2023 Block, Inc.
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
package okhttp3.recipes.kt

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.LoggingEventListener
import org.testcontainers.containers.GenericContainer
import sockslib.client.Socks5
import sockslib.client.SocksSocket


class SocksProxy {
  val withTestContainers: Boolean = false

  val request = Request.Builder()
    .url("https://publicobject.com/helloworld.txt")
    .build()

  fun run() {
    if (withTestContainers) {
      val container = GenericContainer("shadowsocks/shadowsocks-libev:v3.3.5")
        .withExposedPorts(8388)
        .withLogConsumer {
          println(it.utf8String)
        }

      container.use { container ->
        container.start()

        val host: String = container.host
        val port: Int = container.firstMappedPort

        runTestCall(host, port, request)
      }
    } else {
      runTestCall("localhost", 8388, request)
    }
  }

  fun runTestCall(host: String, port: Int, request: Request) {
    val proxy = Socks5(InetSocketAddress(host, port))

    val client = OkHttpClient.Builder()
      .socketFactory(Socks5SocketFactory(proxy))
      .eventListenerFactory(LoggingEventListener.Factory())
      .dns {
        listOf(InetAddress.getLocalHost())
      }
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      for ((name, value) in response.headers) {
        println("$name: $value")
      }

      println(response.body.string())
    }
  }
}

class Socks5SocketFactory(val proxy: Socks5) : SocketFactory() {
  override fun createSocket(): Socket {
    return SocksSocket(proxy)
  }

  override fun createSocket(host: String?, port: Int): Socket {
    TODO("Not implemented")
  }

  override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
    TODO("Not implemented")
  }

  override fun createSocket(host: InetAddress?, port: Int): Socket {
    TODO("Not implemented")
  }

  override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
    TODO("Not implemented")
  }
}

fun main() {
  SocksProxy().run()
}
