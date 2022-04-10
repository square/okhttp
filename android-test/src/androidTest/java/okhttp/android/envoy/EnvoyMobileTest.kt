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

@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp.android.envoy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.envoyproxy.envoymobile.AndroidEngineBuilder
import io.envoyproxy.envoymobile.Engine
import io.envoyproxy.envoymobile.LogLevel
import io.envoyproxy.envoymobile.Standard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.executeAsync
import okio.IOException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Envoy Mobile.
 *
 * https://github.com/envoyproxy/envoy-mobile
 */
class EnvoyMobileTest {
  private lateinit var client: OkHttpClient

  val aiortc = "https://cloudflare-quic.com/b/".toHttpUrl()

  @BeforeEach
  fun buildClient() {
    client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()
  }

  @Test
  fun get() = runTest {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val getRequest = Request(url = aiortc + "get")

    val response = client.newCall(getRequest).executeAsync()

    response.use {
      printResponse(response)
    }

    // assertEquals(Protocol.QUIC, response.protocol)
  }

  @Test
  fun get2() = runTest {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val getRequest = Request(url = "https://http3.is/".toHttpUrl())

    val response = client.newCall(getRequest).executeAsync()

    response.use {
      printResponse(response)
    }

    val response2 = client.newCall(getRequest).executeAsync()

    response.use {
      printResponse(response2)
    }

    // assertEquals(Protocol.QUIC, response2.protocol)
  }

  @Test
  fun post() = runTest {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val postRequest =
      Request(
        url = aiortc + "post",
        body = "{}".toRequestBody("application/json".toMediaType())
      )

    val response = client.newCall(postRequest).executeAsync()

    response.use {
      printResponse(response)
    }
  }

  @Test
  fun cancel() = runTest {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val getRequest = Request(url = aiortc + "delay/30")

    try {
      withTimeout(5.seconds) {
        client.newCall(getRequest).executeAsync()
      }
      fail("Request should have failed")
    } catch (tce: TimeoutCancellationException) {
      // expected
    }
  }

  @Test
  fun enqueue() = runTest {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val requests = 10
    val latch = CountDownLatch(requests)

    val failureChannel = Channel<IOException>(requests)

    repeat(requests) {
      val getRequest = Request(url = aiortc + "get?id=$it")
      client.newCall(getRequest).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          failureChannel.trySend(e)
          latch.countDown()
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            printResponse(response)
          }
          latch.countDown()
        }
      })
    }

    latch.await(20, TimeUnit.SECONDS)
    failureChannel.close()

    assertEquals(listOf<IOException>(), failureChannel.toList())
  }

  private fun printResponse(response: Response) {
    println(response)
    println(response.headers)
    println(response.body.contentType())
    println(response.body.string())
  }

  companion object {
    private lateinit var engine: Engine

    @BeforeAll
    @JvmStatic
    fun setup() {
      val application = ApplicationProvider.getApplicationContext<Application>()

      engine = AndroidEngineBuilder(application, baseConfiguration = Standard())
        .addLogLevel(LogLevel.INFO)
        .setLogger { println(it) }
        // .enableHappyEyeballs(true)
        .build()
    }

    @AfterAll
    @JvmStatic
    fun teardown() {
      engine.terminate()
    }
  }
}

private operator fun HttpUrl.plus(link: String): HttpUrl {
  return this.resolve(link)!!
}
