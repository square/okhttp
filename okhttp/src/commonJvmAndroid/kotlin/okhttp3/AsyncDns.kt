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
package okhttp3

import okhttp3.internal.BlockingAsyncDns
import okio.IOException

/**
 * An asynchronous domain name service that resolves a host name to [DnsResult]s.
 *
 * Unlike [Dns], which returns only `List<InetAddress>`, an `AsyncDns` delivers richer DNS data —
 * including HTTPS/SVCB service records carrying connection hints and Encrypted Client Hello (ECH)
 * configuration. Because the data is delivered by value through [DnsCallback], an `AsyncDns` that
 * wraps and forwards another `AsyncDns` preserves all of it without extra work.
 *
 * Typical implementations are backed by Android's `DnsResolver`, OkHttp's DnsOverHttps, or other
 * resolver libraries. Implementations must be safe for concurrent use.
 */
internal fun interface AsyncDns {
  /**
   * Returns a new, cold [DnsCall] for [hostname]. No work is performed until the call is enqueued.
   *
   * When [addressesOnly] is true the caller needs only IP addresses, so the resolver may skip
   * HTTPS/SVCB queries (and the ECH configuration they carry). Resolvers that ignore this flag and
   * always return everything are still correct.
   */
  fun newCall(
    hostname: String,
    addressesOnly: Boolean,
  ): DnsCall

  /** A single in-flight DNS resolution. */
  interface DnsCall {
    /** The host name being resolved. */
    val hostname: String

    /**
     * Starts resolution and delivers results to [callback]. The callback may be invoked more than
     * once; the final invocation has `hasMore = false`.
     */
    fun enqueue(callback: DnsCallback)

    /** Best-effort cancellation of in-flight queries. Safe to call more than once. */
    fun cancel()
  }

  /** Receives the results of a [DnsCall]. */
  interface DnsCallback {
    /**
     * A batch of [results]. When [hasMore] is true further batches will arrive for this call (for
     * example `A` records now and `AAAA`/HTTPS records later), so consumers may begin connecting
     * before resolution completes.
     */
    fun onResults(
      call: DnsCall,
      results: List<DnsResult>,
      hasMore: Boolean,
    )

    /**
     * A failure for this call. When [hasMore] is true other batches may still succeed.
     */
    fun onFailure(
      call: DnsCall,
      e: IOException,
      hasMore: Boolean,
    )
  }

  companion object {
    /**
     * Adapts this [AsyncDns] to the blocking [Dns] interface. Only [DnsResult.Address] values are
     * returned; HTTPS/SVCB metadata such as ECH is dropped because [Dns] cannot carry it.
     */
    fun AsyncDns.asBlocking(): Dns = BlockingAsyncDns(this)
  }
}
