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

package okhttp3.android.internal

import java.net.InetAddress
import okhttp3.Call
import okhttp3.ExperimentalOkHttpApi
import okio.IOException

/**
 * An async domain name service that resolves IP addresses for host names.
 *
 * The main implementations will typically be implemented using specific DNS libraries such as
 *  * Android DnsResolver
 *  * OkHttp DnsOverHttps
 *  * dnsjava Resolver
 *
 * Implementations of this interface must be safe for concurrent use.
 */
@ExperimentalOkHttpApi
internal interface AsyncDns {
  /**
   * Query DNS records for `hostname`, in the order they are received.
   */
  fun query(
    hostname: String,
    originatingCall: Call?,
    callback: Callback,
  )

  /**
   * Callback to receive results from the DNS Queries.
   */
  @ExperimentalOkHttpApi
  interface Callback {
    /**
     * Invoked on a successful result from a single lookup step.
     *
     * @param addresses a non-empty list of addresses
     * @param hasMore true if another call to onAddresses or onFailure will be made
     */
    fun onAddresses(
      hasMore: Boolean,
      hostname: String,
      addresses: List<InetAddress>,
    )

    /**
     * Invoked on a failed result from a single lookup step.
     *
     * @param hasMore true if another call to onAddresses or onFailure will be made
     */
    fun onFailure(
      hasMore: Boolean,
      hostname: String,
      e: IOException,
    )
  }
}
