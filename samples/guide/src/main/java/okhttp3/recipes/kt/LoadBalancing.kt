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
package okhttp3.recipes.kt

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync

class LoadBalancing {
  private val inFlightRequests = AtomicInteger(0)

  private val client = OkHttpClient.Builder()
    .eventListener(object : EventListener() {
      override fun connectionAcquired(call: Call, connection: Connection) {
        val count = inFlightRequests.incrementAndGet()

        println("Concurrent: $count")
      }

      override fun connectionReleased(call: Call, connection: Connection) {
        inFlightRequests.decrementAndGet()
      }
    })
    .build()
  private val loadBalancingCallFactory = LoadBalancer(client) {
    it.url.pathSegments.last().toInt()
  }

  val url = "https://picsum.photos/".toHttpUrl()

  fun run() = runBlocking {
    val responsesAsync = (1..99).map {
      async {
        val request = Request.Builder()
          .url(url.resolve(it.toString())!!)
          .build()

        try {
          val response = loadBalancingCallFactory.newCall(request).executeAsync()

          // consume body
          response.body.bytes()
          true
        } catch (ioe: IOException) {
          false
        }
      }
    }

    val responses = responsesAsync.awaitAll()

    println("Success: " + responses.count { it })
    println("Failed: " + responses.count { !it })

    loadBalancingCallFactory.close()

    client.dispatcher.executorService.shutdownNow()
    client.connectionPool.evictAll()
  }
}

class LoadBalancer(
  private val rootClient: OkHttpClient,
  private val requestDirector: (Request) -> Int?
) : Call.Factory {
  val clients = ConcurrentHashMap<Int, OkHttpClient>()

  override fun newCall(request: Request): Call {
    val clientKey = requestDirector(request)

    val client = clientForKey(clientKey)

    return client.newCall(request)
  }

  private fun clientForKey(clientKey: Int?) = if (clientKey == null) {
    rootClient
  } else {
    clients.computeIfAbsent(clientKey) {
      rootClient
        .newBuilder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool())
        .build()
    }
  }

  fun close() {
    val clientIterator = clients.values.iterator()
    while (clientIterator.hasNext()) {
      val client = clientIterator.next()
      clientIterator.remove()
      client.dispatcher.executorService.shutdownNow()
      client.connectionPool.evictAll()
    }
  }
}

fun main() {
  LoadBalancing().run()
}
