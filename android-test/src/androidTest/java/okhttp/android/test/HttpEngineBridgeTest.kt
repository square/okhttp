/*
 * Copyright (C) 2025 Block, Inc.
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

import android.content.Context
import android.net.http.ConnectionMigrationOptions
import android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED
import android.net.http.DnsOptions
import android.net.http.DnsOptions.DNS_OPTION_ENABLED
import android.net.http.HttpEngine
import android.net.http.QuicOptions
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.httpengine.HttpEngineCallDecorator.Companion.callDecorator
import okhttp3.coroutines.executeAsync
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class HttpEngineBridgeTest {
  val context = ApplicationProvider.getApplicationContext<Context>()

  val httpEngine =
    HttpEngine
      .Builder(context)
      .setStoragePath(
        context.filesDir
          .resolve("httpEngine")
          .apply {
            mkdirs()
          }.path,
      ).setConnectionMigrationOptions(
        ConnectionMigrationOptions
          .Builder()
          .setAllowNonDefaultNetworkUsage(MIGRATION_OPTION_ENABLED)
          .setDefaultNetworkMigration(MIGRATION_OPTION_ENABLED)
          .setPathDegradationMigration(MIGRATION_OPTION_ENABLED)
          .build(),
      ).addQuicHint("www.google.com", 443, 443)
      .addQuicHint("google.com", 443, 443)
      .setDnsOptions(
        DnsOptions
          .Builder()
          .setPersistHostCache(DNS_OPTION_ENABLED)
          .setPreestablishConnectionsToStaleDnsResults(DNS_OPTION_ENABLED)
          .setUseHttpStackDnsResolver(DNS_OPTION_ENABLED)
          .setStaleDnsOptions(
            DnsOptions.StaleDnsOptions
              .Builder()
              .setUseStaleOnNameNotResolved(DNS_OPTION_ENABLED)
              .build(),
          ).build(),
      ).setEnableQuic(true)
      .setQuicOptions(
        QuicOptions
          .Builder()
          .addAllowedQuicHost("www.google.com")
          .addAllowedQuicHost("google.com")
          .build(),
      ).build()

  var client =
    OkHttpClient
      .Builder()
      .addCallDecorator(httpEngine.callDecorator)
      .build()

  val imageUrls =
    listOf(
      "https://storage.googleapis.com/cronet/sun.jpg",
      "https://storage.googleapis.com/cronet/flower.jpg",
      "https://storage.googleapis.com/cronet/chair.jpg",
      "https://storage.googleapis.com/cronet/white.jpg",
      "https://storage.googleapis.com/cronet/moka.jpg",
      "https://storage.googleapis.com/cronet/walnut.jpg",
    ).map { it.toHttpUrl() }

  @Test
  fun testNewCall() =
    runTest {
      val call = client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

      val response = call.executeAsync()

      println(response.body.string().take(40))

      val call2 = client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

      val response2 = call2.executeAsync()

      println(response2.body.string().take(40))
      println(response2.protocol)
    }

  @Test
  fun testWithCache() =
    runTest {
      client =
        client
          .newBuilder()
          .cache(Cache(FakeFileSystem(), "/cache".toPath(), 100_000_000))
          .build()

      repeat(10) {
        imageUrls.forEach {
          val call = client.newCall(Request(it))

          val response = call.executeAsync()

          println(
            "${response.request.url} cached=${response.cacheResponse != null} " +
              response.body
                .byteString()
                .md5()
                .hex(),
          )
        }
      }
    }
}
