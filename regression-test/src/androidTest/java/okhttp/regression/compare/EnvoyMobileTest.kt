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

package okhttp.regression.compare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.envoyproxy.envoymobile.AndroidEngineBuilder
import io.envoyproxy.envoymobile.Engine
import io.envoyproxy.envoymobile.LogLevel
import io.envoyproxy.envoymobile.Standard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.*
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
  private lateinit var engine: Engine

  @Before
  fun setup() {
    val application = ApplicationProvider.getApplicationContext<Application>()

    engine = AndroidEngineBuilder(application, baseConfiguration = Standard())
      .addLogLevel(LogLevel.TRACE)
      .setOnEngineRunning { println("Envoy async internal setup completed") }
      .setLogger { println(it) }
      .build()
  }

  @After
  fun teardown() {
    engine.terminate()
  }

  @Test
  fun get() {
    runBlocking {
      val getRequest = Request.Builder().url("https://quic.aiortc.org/10").build()

      val response = withContext(Dispatchers.IO) {
        makeRequest(engine, getRequest)
      }
      printResponse(response)

      val response1 = withContext(Dispatchers.IO) {
        makeRequest(engine, getRequest)
      }
      printResponse(response1)

      val postRequest = Request.Builder()
        .url("https://quic.aiortc.org/httpbin/post")
        .post(RequestBody.create(MediaType.get("application/json"), "{}"))
        .build()
      val response2 = withContext(Dispatchers.IO) {
        makeRequest(engine, postRequest)
      }
      printResponse(response2)
    }
  }

  @Test
  fun getInterceptor() {
    val client = OkHttpClient.Builder()
      .addInterceptor(EnvoyInterceptor(engine))
      .build()

    runBlocking {
      val getRequest = Request.Builder().url("https://quic.aiortc.org/10").build()

      val response = withContext(Dispatchers.IO) {
        client.newCall(getRequest).execute()
      }
      printResponse(response)

      val response1 = withContext(Dispatchers.IO) {
        client.newCall(getRequest).execute()
      }
      printResponse(response1)

      val postRequest = Request.Builder()
        .url("https://quic.aiortc.org/httpbin/post")
        .post(RequestBody.create(MediaType.get("application/json"), "{}"))
        .build()
      val response2 = withContext(Dispatchers.IO) {
        client.newCall(postRequest).execute()
      }
      printResponse(response2)
    }
  }

  private fun printResponse(response: Response) {
    println(response)
    println(response.headers())
    println(response.body()?.contentType())
    println(response.body()?.string())
  }
}
