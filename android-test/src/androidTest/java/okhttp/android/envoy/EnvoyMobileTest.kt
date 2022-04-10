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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.envoyproxy.envoymobile.AndroidEngineBuilder
import io.envoyproxy.envoymobile.Engine
import io.envoyproxy.envoymobile.LogLevel
import io.envoyproxy.envoymobile.Standard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Envoy Mobile.
 *
 * https://github.com/envoyproxy/envoy-mobile
 */
@RunWith(AndroidJUnit4::class)
class EnvoyMobileTest {
  private lateinit var client: OkHttpClient
  private lateinit var engine: Engine

  @Before
  fun setup() {
    val application = ApplicationProvider.getApplicationContext<Application>()

    engine = AndroidEngineBuilder(application, baseConfiguration = Standard())
      .addLogLevel(LogLevel.TRACE)
      .setOnEngineRunning { println("Envoy async internal setup completed") }
      .setLogger { println(it) }
      .build()

    client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()
  }

  @After
  fun teardown() {
    engine.terminate()
  }

  @Test
  fun getInterceptor() = runTest {
    val aiortc = "https://quic.aiortc.org".toHttpUrl()

    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    val getRequest = Request(url = aiortc / "/httpbin/get")

    val response = client.newCall(getRequest).executeAsync()
    printResponse(response)

    val response1 = client.newCall(getRequest).executeAsync()
    printResponse(response1)

    val postRequest =
      Request(
        url = aiortc / "/httpbin/post",
        body = "{}".toRequestBody("application/json".toMediaType())
      )

    val response2 = client.newCall(postRequest).executeAsync()

    printResponse(response2)
  }

  private fun printResponse(response: Response) {
    println(response)
    println(response.headers)
    println(response.body.contentType())
    println(response.body.string())
  }
}

private operator fun HttpUrl.div(link: String): HttpUrl {
  return this.resolve(link)!!
}
