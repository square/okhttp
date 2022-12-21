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
package okhttp.android.test

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
class CookiesTest {
  var cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
  val cookieJar = JavaNetCookieJar(cookieManager)

  private var client: OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .build()

  @AfterEach
  fun tearDown() {
    client.close()
  }

  @Test
  fun testRequest() {
    // TODO remove
    val request = Request.Builder().url("https://developer.android.com/training/multiscreen/screendensities").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }

    val cookies = cookieJar.loadForRequest(request.url)
    assertThat(cookies).isNotEmpty

    val response2 = client.newCall(request).execute()

    response2.use {
      assertEquals(200, response2.code)
    }
  }


  @Test
  fun skipsInvalidCookie() {
    val url = "https://www.squareup.com/".toHttpUrl()

    cookieManager.put(
      URI(url.toString()),
      mapOf("Set-Cookie" to listOf("a= android ; Domain=.squareup.com; Path=/"))
    )
    val actualCookies = cookieJar.loadForRequest(url)
    assertThat(actualCookies.size).isEqualTo(1)
    assertThat(actualCookies[0].name).isEqualTo("a")
    assertThat(actualCookies[0].value).isEqualTo("android")
  }

  fun OkHttpClient.close() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
  }
}
