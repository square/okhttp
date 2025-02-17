/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.android.internal

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Call
import okio.IOException

internal class CombinedAsyncDns(val dnsList: List<AsyncDns>) : AsyncDns {
  override fun query(
    hostname: String,
    originatingCall: Call?,
    callback: AsyncDns.Callback,
  ) {
    var remainingQueries = dnsList.size
    val lock = Any()

    if (dnsList.isEmpty()) {
      callback.onFailure(false, hostname, UnknownHostException("No configured dns options"))
      return
    }

    dnsList.forEach {
      it.query(
        hostname = hostname,
        originatingCall = originatingCall,
        callback =
          object : AsyncDns.Callback {
            override fun onAddresses(
              hasMore: Boolean,
              hostname: String,
              addresses: List<InetAddress>,
            ) {
              synchronized(lock) {
                if (!hasMore) {
                  remainingQueries -= 1
                }

                callback.onAddresses(hasMore = remainingQueries > 0, hostname = hostname, addresses = addresses)
              }
            }

            override fun onFailure(
              hasMore: Boolean,
              hostname: String,
              e: IOException,
            ) {
              synchronized(lock) {
                if (!hasMore) {
                  remainingQueries -= 1
                }

                callback.onFailure(hasMore = remainingQueries > 0, hostname = hostname, e = e)
              }
            }
          },
      )
    }
  }

  companion object {
    /**
     * Returns an [AsyncDns] that queries all [sources] in parallel, and calls
     * the callback for each partial result.
     *
     * The callback will be passed `hasMore = false` only when all sources
     * have no more results.
     *
     * @param sources one or more AsyncDns sources to query.
     */
    fun union(vararg sources: AsyncDns): AsyncDns {
      return if (sources.size == 1) {
        sources.first()
      } else {
        CombinedAsyncDns(sources.toList())
      }
    }
  }
}
