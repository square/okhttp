/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.loom

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CompletableFuture
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class LoomBackendTest {
  private var assertVirtual = false

  val client = LoomClientBuilder.clientBuilder()
    .eventListener(object : EventListener() {
      override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        assertVirtual()
      }

      override fun requestHeadersStart(call: Call) {
        assertVirtual()
      }

      override fun responseHeadersStart(call: Call) {
        assertVirtual()
      }
    })
    .build()

  fun assertVirtual() {
    if (assertVirtual) {
      assertThat(Thread.currentThread().isVirtual).isTrue()
    }
  }

  @Test
  fun makeExecuteRequest() {
    val testThread = Thread.currentThread()

    val response =
      client.newCall(Request("https://www.google.com/robots.txt".toHttpUrl())).execute()

    assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
    assertThat(response.body.string()).contains("Disallow")
  }

  @Test
  fun makeEnqueueRequest() {
    assertVirtual = true

    val completableFuture = CompletableFuture<String>()

    val response =
      client.newCall(Request("https://www.google.com/robots.txt".toHttpUrl())).enqueue(
        object: Callback {
          override fun onFailure(call: Call, e: IOException) {
            completableFuture.completeExceptionally(e)
          }

          override fun onResponse(call: Call, response: Response) {
            completableFuture.complete(response.body.string())
          }
        }
      )

    assertThat(completableFuture.get()).contains("Disallow")
  }
}
