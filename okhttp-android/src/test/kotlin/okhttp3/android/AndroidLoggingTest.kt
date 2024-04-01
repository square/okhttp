/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
package okhttp3.android

import android.util.Log
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isNull
import java.net.UnknownHostException
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class AndroidLoggingTest {
  val clientBuilder =
    OkHttpClient.Builder()
      .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
      .dns {
        throw UnknownHostException("shortcircuit")
      }

  val request = Request("http://google.com/robots.txt".toHttpUrl())

  @Test
  fun testHttpLoggingInterceptor() {
    val interceptor =
      HttpLoggingInterceptor.androidLogging(tag = "testHttpLoggingInterceptor").apply {
        level = HttpLoggingInterceptor.Level.BASIC
      }

    val client = clientBuilder.addInterceptor(interceptor).build()

    try {
      client.newCall(request).execute()
    } catch (uhe: UnknownHostException) {
      // expected
    }

    val logs = ShadowLog.getLogsForTag("testHttpLoggingInterceptor")
    assertThat(logs.map { it.type }).containsOnly(Log.INFO)
    assertThat(logs.map { it.msg }).containsExactly(
      "--> GET http://google.com/robots.txt",
      "<-- HTTP FAILED: java.net.UnknownHostException: shortcircuit",
    )
    // We should consider if these logs should retain Exceptions
    assertThat(logs.last().throwable).isNull()
  }

  @Test
  fun testLoggingEventListener() {
    val client =
      clientBuilder.eventListenerFactory(LoggingEventListener.androidLogging(tag = "testLoggingEventListener")).build()

    try {
      client.newCall(request).execute()
    } catch (uhe: UnknownHostException) {
      // expected
    }

    val logs = ShadowLog.getLogsForTag("testLoggingEventListener")
    assertThat(logs.map { it.type }).containsOnly(Log.INFO)
    assertThat(
      logs.map {
        it.msg.replace(
          "\\[\\d+ ms] ".toRegex(),
          "",
        )
      },
    ).containsExactly(
      "callStart: Request{method=GET, url=http://google.com/robots.txt}",
      "proxySelectStart: http://google.com/",
      "proxySelectEnd: [DIRECT]",
      "dnsStart: google.com",
      "callFailed: java.net.UnknownHostException: shortcircuit",
    )
    // We should consider if these logs should retain Exceptions
    assertThat(logs.last().throwable).isNull()
  }
}
