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
package okhttp3.android

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.dns.HttpsEndpoint
import android.os.CancellationSignal
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.AsyncDns
import okhttp3.DnsResult
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.PlatformRegistry
import okio.ByteString.Companion.toByteString

/**
 * An [AsyncDns] backed by Android's [DnsResolver].
 *
 * A single resolution issues independent queries: `A` and `AAAA` for the host's authoritative IP
 * addresses, and an HTTPS/SVCB (type 65) query for the service record carrying Encrypted Client
 * Hello (ECH) configuration. The A/AAAA answers are combined into one address [DnsResult] batch and
 * the HTTPS answer into another; the last batch to complete is reported with `hasMore = false`.
 *
 * If Android's [DnsResolver] returns no addresses — for names it can't resolve (`localhost`,
 * loopback/literal IPs) or rejects outright (lenient names such as `under_score.example.com`, which
 * it fails via `IDN.toASCII`) — address resolution falls back to the JVM system resolver, so this
 * stays a superset of `okhttp3.Dns.SYSTEM` while still adding ECH data for real hostnames.
 *
 * Available on Android 16 (API 36) and newer; ECH application additionally requires API 37.
 */
@Suppress("NewApi")
@RequiresApi(36)
@SuppressSignatureCheck
internal class AndroidAsyncDns
  @RequiresApi(36)
  internal constructor(
    private val dnsResolver: DnsResolver? = null,
    private val executor: Executor = Executor { it.run() },
    private val timeoutMillis: Int = 5_000,
  ) : AsyncDns {
    private val activeDnsResolver: DnsResolver by lazy {
      dnsResolver ?: HandlerThread("OkHttp AsyncDns").let { handlerThread ->
        handlerThread.start()
        DnsResolver(PlatformRegistry.applicationContext!!, handlerThread.looper)
      }
    }

    override fun newCall(
      hostname: String,
      addressesOnly: Boolean,
    ): AsyncDns.DnsCall = AndroidDnsCall(hostname, addressesOnly)

    private inner class AndroidDnsCall(
      override val hostname: String,
      private val addressesOnly: Boolean,
    ) : AsyncDns.DnsCall {
      private val cancellationSignal = CancellationSignal()

      override fun enqueue(callback: AsyncDns.DnsCallback) {
        // Two result streams feed the callback: the addresses (A + AAAA, aggregated, with a
        // system-resolver fallback) and, unless addresses-only, the HTTPS/SVCB record carrying ECH.
        // The last stream to finish reports hasMore = false.
        val remaining = AtomicInteger(if (addressesOnly) 1 else 2)
        resolveAddresses(callback, remaining)
        if (!addressesOnly) queryHttps(callback, remaining)
      }

      override fun cancel() {
        cancellationSignal.cancel()
      }

      /**
       * Resolves `A` and `AAAA` through Android's [DnsResolver], then reports them as a single
       * address batch via [finishAddresses]. The two queries run independently; we wait for both
       * before reporting so the fallback can tell "the resolver returned nothing" from "one family
       * is simply absent".
       */
      private fun resolveAddresses(
        callback: AsyncDns.DnsCallback,
        remaining: AtomicInteger,
      ) {
        val call = this
        val addresses = mutableListOf<InetAddress>()
        // Outstanding A/AAAA queries; the one that brings this to zero reports the batch.
        val pendingQueries = AtomicInteger(2)

        val queryCallback =
          object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(
              answer: List<InetAddress>,
              rcode: Int,
            ) {
              synchronized(addresses) { addresses += answer }
              if (pendingQueries.decrementAndGet() == 0) finishAddresses(call, callback, remaining, addresses)
            }

            override fun onError(e: DnsResolver.DnsException) {
              // A per-family failure (e.g. NXDOMAIN for the missing family) isn't fatal on its own;
              // finishAddresses decides based on the combined result.
              if (pendingQueries.decrementAndGet() == 0) finishAddresses(call, callback, remaining, addresses)
            }
          }

        for (type in intArrayOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
          try {
            activeDnsResolver.query(
              null,
              hostname,
              type,
              DnsResolver.FLAG_EMPTY,
              executor,
              cancellationSignal,
              queryCallback,
            )
          } catch (e: Exception) {
            // DnsResolver.query can reject the hostname synchronously (e.g. IDN.toASCII on an
            // underscore name). Count it as a completed-but-empty query so the fallback runs.
            if (pendingQueries.decrementAndGet() == 0) finishAddresses(call, callback, remaining, addresses)
          }
        }
      }

      /**
       * Reports the resolved [addresses] as one batch. When Android's [DnsResolver] produced none,
       * falls back to the JVM system resolver ([InetAddress.getAllByName]) so that `localhost`,
       * loopback/literal IPs and lenient names resolve exactly as they do under `Dns.SYSTEM`. Called
       * exactly once per resolution, after both the A and AAAA queries have settled.
       */
      private fun finishAddresses(
        call: AsyncDns.DnsCall,
        callback: AsyncDns.DnsCallback,
        remaining: AtomicInteger,
        addresses: List<InetAddress>,
      ) {
        val resolved = synchronized(addresses) { addresses.toList() }
        if (resolved.isNotEmpty()) {
          callback.onResults(call, resolved.map { DnsResult.Address(it) }, remaining.last())
          return
        }

        try {
          val systemAddresses = InetAddress.getAllByName(hostname).toList()
          callback.onResults(call, systemAddresses.map { DnsResult.Address(it) }, remaining.last())
        } catch (e: Exception) {
          callback.onFailure(call, hostname.toUnknownHostException(e), remaining.last())
        }
      }

      private fun queryHttps(
        callback: AsyncDns.DnsCallback,
        remaining: AtomicInteger,
      ) {
        val call = this
        try {
          @Suppress("WrongConstant")
          activeDnsResolver.query(
            null,
            hostname,
            DnsResolver.FLAG_EMPTY,
            executor,
            timeoutMillis,
            cancellationSignal,
            object : DnsResolver.Callback<HttpsEndpoint> {
              override fun onAnswer(
                answer: HttpsEndpoint,
                rcode: Int,
              ) {
                callback.onResults(call, answer.toHttpsServices(), remaining.last())
              }

              override fun onError(e: DnsResolver.DnsException) {
                // ECH is best-effort; a missing/failed HTTPS record is not a lookup failure.
                callback.onResults(call, listOf(), remaining.last())
              }
            },
          )
        } catch (e: Exception) {
          callback.onResults(call, listOf(), remaining.last())
        }
      }
    }
  }

/** Decrements the outstanding-query counter and returns whether further batches will follow. */
private fun AtomicInteger.last(): Boolean = decrementAndGet() > 0

@SuppressLint("NewApi")
private fun HttpsEndpoint.toHttpsServices(): List<DnsResult.HttpsService> =
  httpsRecords.map { record ->
    val ech =
      try {
        record.echConfigList?.toBytes()?.toByteString()
      } catch (e: IllegalArgumentException) {
        // The platform can throw on a malformed or absent ECH parameter.
        // https://issuetracker.google.com/issues/319957694
        null
      }
    DnsResult.HttpsService(ech = ech)
  }

private fun String.toUnknownHostException(cause: Throwable): UnknownHostException =
  UnknownHostException("DNS lookup failed for $this").apply {
    initCause(cause)
  }
