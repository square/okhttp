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
import android.net.http.DnsOptions
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import okio.Buffer
import org.junit.After
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

  val cacheDir =
    context.cacheDir.resolve("httpEngine").also {
      it.mkdirs()
    }
  val engine =
    HttpEngine.Builder(context)
      .setEnableBrotli(true)
      .setStoragePath(cacheDir.path)
      .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 10_000_000)
      .setConnectionMigrationOptions(
        ConnectionMigrationOptions.Builder()
          .setDefaultNetworkMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
          .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
          .setAllowNonDefaultNetworkUsage(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
          .build(),
      )
      .setDnsOptions(
        DnsOptions.Builder()
          .setUseHttpStackDnsResolver(DnsOptions.DNS_OPTION_ENABLED)
          .setStaleDns(DnsOptions.DNS_OPTION_ENABLED)
          .setPersistHostCache(DnsOptions.DNS_OPTION_ENABLED)
          .build(),
      )
      .setQuicOptions(
        QuicOptions.Builder()
          .addAllowedQuicHost("google.com")
          .addAllowedQuicHost("www.google.com")
          .build(),
      )
      .addQuicHint("google.com", 443, 443)
      .addQuicHint("www.google.com", 443, 443)
      .build()

  @After
  fun tearDown() {
    engine.shutdown()
    cacheDir.deleteRecursively()
  }

  @Test
  fun get() {
    val executor = Executors.newCachedThreadPool()

    val completableFuture = execute(engine, executor, "https://google.com/robots.txt")

    try {
      val response = completableFuture.get(10, TimeUnit.SECONDS)

      assertEquals(200, response.code)
      assertEquals("h3", response.negotiatedProtocol)
      assertTrue(response.content.contains("Disallow"))
    } catch (ee: ExecutionException) {
      throw ee.cause?.cause ?: ee.cause!!
    }
  }

  data class Response(
    val code: Int,
    val negotiatedProtocol: String,
    val content: String,
  )

  private fun execute(
    engine: HttpEngine,
    executor: ExecutorService,
    url: String,
  ): CompletableFuture<Response> {
    val completableFuture = CompletableFuture<Response>()
    val buffer = Buffer()

    val req =
      engine.newUrlRequestBuilder(
        url,
        executor,
        object : Callback {
          override fun onRedirectReceived(
            request: UrlRequest,
            info: UrlResponseInfo,
            newLocationUrl: String,
          ) {
            println("request " + info.httpStatusCode + " " + newLocationUrl)
            request.followRedirect()
          }

          override fun onResponseStarted(
            request: UrlRequest,
            info: UrlResponseInfo,
          ) {
            println("onResponseStarted ${info.headers.asMap} ${info.negotiatedProtocol}")
            request.read(ByteBuffer.allocateDirect(4096 * 8))
          }

          override fun onReadCompleted(
            request: UrlRequest,
            info: UrlResponseInfo,
            byteBuffer: ByteBuffer,
          ) {
            println("onReadCompleted ${info.headers.asMap}")
            byteBuffer.flip()
            buffer.write(byteBuffer)
            byteBuffer.clear()
            request.read(byteBuffer)
          }

          override fun onSucceeded(
            request: UrlRequest,
            info: UrlResponseInfo,
          ) {
            println("onSucceeded ${info.headers.asMap}")
            completableFuture.complete(Response(info.httpStatusCode, info.negotiatedProtocol, buffer.readUtf8()))
          }

          override fun onFailed(
            request: UrlRequest,
            info: UrlResponseInfo?,
            error: HttpException,
          ) {
            println("onSucceeded ${info?.headers?.asMap}")
            completableFuture.completeExceptionally(error)
          }

          override fun onCanceled(
            request: UrlRequest,
            info: UrlResponseInfo?,
          ) {
            completableFuture.completeExceptionally(CancellationException())
          }
        },
      )
        .setPriority(REQUEST_PRIORITY_MEDIUM)
        .setDirectExecutorAllowed(true)
        .setTrafficStatsTag(101)
        .build()

    req.start()
    return completableFuture
  }

  @Test
  fun urlConnection() {
    val conn = engine.openConnection(URL("https://google.com/robots.txt")) as HttpURLConnection

    val text =
      conn.inputStream.use {
        it.bufferedReader().readText()
      }

    assertEquals(200, conn.responseCode)

    assertTrue(text.contains("Disallow"))
  }
}
