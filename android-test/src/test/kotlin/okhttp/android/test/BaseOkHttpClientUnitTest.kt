/*
 * Copyright (c) 2025 Block, Inc.
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
package okhttp.android.test

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Test

abstract class BaseOkHttpClientUnitTest {
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    client =
      OkHttpClient
        .Builder()
        .cache(Cache(FakeFileSystem(), "/cache".toPath(), 10_000_000))
        .build()
  }

  @Test
  fun testRequestExternal() {
    assumeNetwork()

    val request = Request("https://www.google.com/robots.txt".toHttpUrl())

    val networkRequest =
      request
        .newBuilder()
        .build()

    val call = client.newCall(networkRequest)

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.cacheResponse).isNull()
    }

    val cachedCall = client.newCall(request)

    cachedCall.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.cacheResponse).isNotNull()
    }
  }

  @Test
  open fun testPublicSuffixDb() {
    val httpUrl = "https://www.google.co.uk".toHttpUrl()
    assertThat(httpUrl.topPrivateDomain()).isEqualTo("google.co.uk")
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw AssumptionViolatedException(uhe.message, uhe)
    }
  }
}
