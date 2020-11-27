/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package okhttp.regression.compare

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.envoyproxy.envoymobile.AndroidEngineBuilder
import io.envoyproxy.envoymobile.RequestHeadersBuilder
import io.envoyproxy.envoymobile.RequestMethod
import io.envoyproxy.envoymobile.UpstreamHttpProtocol
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * Simplified from
 * https://github.com/envoyproxy/envoy-mobile/blob/main/examples/kotlin/hello_world/MainActivity.kt
 *
 * Mainly intended to verify behaviour of popular clients across Android versions, similar
 * to observing Firefox or Chrome browser behaviour.
 */
@RunWith(AndroidJUnit4::class)
class EnvoyMobileTest {
  val REQUEST_HANDLER_THREAD_NAME = "hello_envoy_kt"
  val REQUEST_AUTHORITY = "api.lyft.com"
  val REQUEST_PATH = "/ping"
  val REQUEST_SCHEME = "https"

  @Test
  fun testHttp2() {
    val application =  ApplicationProvider.getApplicationContext() as Application

    val engine = AndroidEngineBuilder(application)
      .setOnEngineRunning { Log.d("MainActivity", "Envoy async internal setup completed") }
      .build()

    val requestHeaders = RequestHeadersBuilder(
      RequestMethod.GET, REQUEST_SCHEME, REQUEST_AUTHORITY, REQUEST_PATH
    )
      .addUpstreamHttpProtocol(UpstreamHttpProtocol.HTTP2)
      .build()
    engine
      .streamClient()
      .newStreamPrototype()
      .setOnResponseHeaders { responseHeaders, _ ->
        val status = responseHeaders.httpStatus ?: 0L
        val message = "received headers with status $status"

        val sb = StringBuilder()
        for ((name, value) in responseHeaders.headers) {
          sb.append(name).append(": ").append(value.joinToString()).append("\n")
        }
        val headerText = sb.toString()

        Log.d("EnvoyMobileTest", message)
        responseHeaders.value("filter-demo")?.first()?.let { filterDemoValue ->
          Log.d("EnvoyMobileTest", "filter-demo: $filterDemoValue")
        }

        Log.i("EnvoyMobileTest", "" + status)
      }
      .setOnError { error ->
        val attemptCount = error.attemptCount ?: -1
        val message = "failed with error after $attemptCount attempts: ${error.message}"
        Log.d("EnvoyMobileTest", message)
      }
      .start(Executors.newSingleThreadExecutor())
      .sendHeaders(requestHeaders, true)
  }
}
