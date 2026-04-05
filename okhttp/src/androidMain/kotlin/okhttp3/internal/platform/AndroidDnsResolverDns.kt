/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.internal.platform

import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.dns.HttpsEndpoint
import android.net.dns.HttpsRecord
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.EchAware
import okio.ByteString
import okio.ByteString.Companion.toByteString

@Suppress("NewApi")
@RequiresApi(36)
class AndroidDnsResolverDns :
  Dns,
  EchAware {
  val dnsResolver: DnsResolver by lazy {
    val handlerThread = android.os.HandlerThread("DnsLooper").apply { start() }

    DnsResolver(PlatformRegistry.applicationContext!!, handlerThread.looper)
  }

  val httpsRecords: MutableMap<String, Future<HttpsRecord?>> = HashMap()

  override fun lookup(hostname: String): List<InetAddress> {
    val httpsFuture = CompletableFuture<HttpsRecord?>()
    val dnsFuture = CompletableFuture<List<InetAddress>>()

    val callback: Callback<HttpsEndpoint?> =
      object : Callback<HttpsEndpoint?> {
        override fun onAnswer(
          answer: HttpsEndpoint,
          rcode: Int,
        ) {
          if (answer.httpsRecords.isNotEmpty()) {
            if (answer.httpsRecords.size > 1) {
              answer.httpsRecords.forEach {
                println("${it.priority} ${it.targetName} ${it.port} ${it.alpnIds} ${it.ipAddressHints}")
              }
            }
            httpsFuture.complete(answer.httpsRecords.first())
          }
          if (answer.ipAddresses.isNotEmpty()) {
            dnsFuture.complete(answer.ipAddresses)
          }
        }

        override fun onError(p0: DnsResolver.DnsException) {
          if (!dnsFuture.isDone) {
            dnsFuture.completeExceptionally(p0)
          }
          if (!httpsFuture.isDone) {
            httpsFuture.completeExceptionally(p0)
          }
        }
      }
    @Suppress("WrongConstant")
    dnsResolver.query(
      // network =
      null,
      // domain =
      hostname,
      // flags =
      DnsResolver.FLAG_EMPTY,
      // executor =
      { it.run() },
      // httpsTimeoutMillis =
      1_000,
      // cancellationSignal =
      null,
      // callback =
      callback,
    )
    httpsRecords[hostname] = httpsFuture

    // TODO replace with real timeout
    return dnsFuture.get(5, TimeUnit.SECONDS)
  }

  override fun getHostRecords(host: String): Any? {
    val record = httpsRecords[host]?.get()
    val echConfig = record?.echConfigList
    return echConfig
  }
}
