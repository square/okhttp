/*
 * Copyright (c) 2022 Block, Inc.
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

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.ConnectionMigrationOptions
import android.net.http.DnsOptions
import android.net.http.HttpEngine
import android.net.http.QuicOptions
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.net.cronet.okhttptransportU.CronetInterceptor
import java.io.File
import java.net.URL
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.LoggingEventListener
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider

@SuppressLint("NewApi")
fun OkHttpClient.Companion.newAndroidClient(
  context: Context,
  engineConfig: HttpEngine.Builder.() -> Unit = {
    addQuicHint(".googleapis.com", 443, 443)
    addQuicHint(".google.com", 443, 443)
    addQuicHint("www.google.com", 443, 443)
    addQuicHint("storage.googleapis.com", 443, 443)
    addQuicHint("www.googleapis.com", 443, 443)
  },
  debug: Boolean,
  clientConfig: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient {
  val clientBuilder = OkHttpClient.Builder()
    .addInterceptor(TracingInterceptor())
    .addInterceptor(AlwaysOkHttpInterceptor)
    .apply {
      if (debug) {
        eventListenerFactory(LoggingEventListener.AndroidLogging())
      }
    }

  with(clientBuilder) {
    clientConfig()
  }

  // TODO should this be allowed, and we assume implemented in Cronet?
  check(clientBuilder.networkInterceptors().isEmpty()) {
    "Android client does not support network interceptors"
  }

  if (Build.VERSION.PREVIEW_SDK_INT > 0) {
    val engine: HttpEngine = buildAndroidHttpEngine(context, engineConfig)

    clientBuilder.addInterceptor(CronetInterceptor.Builder(engine).build())
  } else if (CronetProvider.getAllProviders(context).isNotEmpty()) {
    val engine: CronetEngine = buildChromiumCronetEngine(context)

    clientBuilder.addInterceptor(com.google.net.cronet.okhttptransport.CronetInterceptor.newBuilder(engine).build())
  } else {
    val cache = Cache(context.cacheDir.resolve("okhttp"), 10_000_000)

    clientBuilder
      .addInterceptor(BrotliInterceptor)
      .connectionPool(ConnectionPool(connectionListener = TracingConnectionListener()))
      .cache(cache)
  }

  return clientBuilder.build()
}

@RequiresApi(34)
private fun buildAndroidHttpEngine(
  context: Context,
  engineConfig: HttpEngine.Builder.() -> Unit
): HttpEngine {
  val engine: HttpEngine = HttpEngine.Builder(context)
    .apply {
      val cacheDir = context.cacheDir.resolve("okhttpCronet").apply {
        mkdirs()
      }
      setStoragePath(cacheDir.absolutePath)
      setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 10_000_000)
      setDnsOptions(
        DnsOptions.Builder()
          .setPersistHostCache(true)
          .build()
      )
      setEnableBrotli(true)
      setConnectionMigrationOptions(
        ConnectionMigrationOptions.Builder()
          .setEnableDefaultNetworkMigration(true)
          .setEnablePathDegradationMigration(true)
          .build()
      )
      setUserAgent("okhttp/${OkHttp.VERSION} Cronet/${HttpEngine.getVersionString()}")
      setEnableQuic(true)
      setQuicOptions(
        QuicOptions.Builder()
          .build()
      )
    }
    .apply(engineConfig)
    .build()

  // Let's play with using this for everything
  URL.setURLStreamHandlerFactory(engine.createURLStreamHandlerFactory())
  return engine
}

private fun buildChromiumCronetEngine(context: Context): CronetEngine {
  val engine: CronetEngine = CronetEngine.Builder(context)
    .apply {
      val cacheDir = context.cacheDir.resolve("okhttpCronetProvider").apply {
        mkdirs()
      }
      setStoragePath(cacheDir.absolutePath)
      enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10_000_000)
      enableBrotli(true)
      enableHttp2(true)
      enableQuic(true)

      // TODO allow some form of config
      addQuicHint(".googleapis.com", 443, 443)
      addQuicHint(".google.com", 443, 443)
      addQuicHint("www.google.com", 443, 443)
      addQuicHint("storage.googleapis.com", 443, 443)
      addQuicHint("www.googleapis.com", 443, 443)
    }
    .build()

  val logFile = File(context.getExternalFilesDir(null), "cronet.log")
//  val logFile = "/storage/emulated/0/Android/media/okhttp.android.test.test/additional_test_output/cronet.log"
  engine.startNetLogToFile(logFile.path, true)

  // Let's play with using this for everything
  URL.setURLStreamHandlerFactory(engine.createURLStreamHandlerFactory())
  return engine
}
