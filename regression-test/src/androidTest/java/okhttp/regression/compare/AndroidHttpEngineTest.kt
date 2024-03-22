/*
 * Copyright (C) 2020 Square, Inc.
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

import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.QuicOptions
import android.net.http.UrlRequest
import android.net.http.UrlRequest.Callback
import android.net.http.UrlRequest.REQUEST_PRIORITY_MEDIUM
import android.net.http.UrlResponseInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Protocol
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android HttpEngine.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class AndroidHttpEngineTest {
  val context = InstrumentationRegistry.getInstrumentation().context

  val engine = HttpEngine.Builder(context)
    .setEnableHttp2(true)
    .setEnableQuic(true)
    .setEnableBrotli(true)
    .setStoragePath(context.cacheDir.resolve("httpEngine").path)
    .setConnectionMigrationOptions(
      ConnectionMigrationOptions.Builder()
        .setDefaultNetworkMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
        .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
        .setAllowNonDefaultNetworkUsage(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
        .build()
    )
    .setQuicOptions(
      QuicOptions.Builder()
        .addAllowedQuicHost("google.com")
        .build()
    )
    .build()

  @Test
  fun get() {
    val executor = Executors.newCachedThreadPool()

    val completableFuture = execute(engine, executor, "https://google.com/robots.txt")

    val (text, code) = completableFuture.get()

    assertEquals(200, code)
    assertTrue(text.contains("Disallow"))
  }

  private fun execute(
    engine: HttpEngine,
    executor: ExecutorService,
    url: String
  ): CompletableFuture<Pair<String, Int>> {
    val completableFuture = CompletableFuture<Pair<String, Int>>()
    val buffer = Buffer()
    var code: Int? = null

    val req = engine.newUrlRequestBuilder(url, executor, object : Callback {
      override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        println("request " + info.httpStatusCode + " " + newLocationUrl)
        request.followRedirect()
      }

      override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        println("onResponseStarted $info")
        code = info.httpStatusCode
      }

      override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        println("onReadCompleted $info")
        buffer.write(byteBuffer)
      }

      override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        completableFuture.complete(Pair(buffer.readUtf8(), code!!))
      }

      override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
        completableFuture.completeExceptionally(error)
      }

      override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        completableFuture.completeExceptionally(CancellationException())
      }
    })
      .setPriority(REQUEST_PRIORITY_MEDIUM)
      .setDirectExecutorAllowed(true)
      .setTrafficStatsTag(101)
      .build()

    req.start()
    return completableFuture
  }

  @Test
  fun urlConnection() {
    val conn = engine.openConnection(URL("https://google.com/robots.txt")) as HttpsURLConnection

    val text = conn.getInputStream().use {
      it.bufferedReader().readText()
    }

    assertEquals(200, conn.responseCode)
    assertEquals(Protocol.HTTP_2, conn.requestProperties)

    assertTrue(text.contains("Disallow"))
  }
}
