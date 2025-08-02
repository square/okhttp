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
 */
package okhttp3.android.internal

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import okhttp3.Dns
import okhttp3.internal.SuppressSignatureCheck

@RequiresApi(Build.VERSION_CODES.Q)
@SuppressSignatureCheck
internal class AndroidDns(
  val network: Network,
) : Dns {
  // API 29+
  private val dnsResolver = DnsResolver.getInstance()

  override fun lookup(hostname: String): List<InetAddress> {
    // API 24+
    val result = CompletableFuture<List<InetAddress>>()

    dnsResolver.query(
      network,
      hostname,
      DnsResolver.FLAG_EMPTY,
      { it.run() },
      null,
      object : DnsResolver.Callback<List<InetAddress>> {
        override fun onAnswer(
          answer: List<InetAddress>,
          rcode: Int,
        ) {
          result.complete(answer)
        }

        override fun onError(error: DnsResolver.DnsException) {
          result.completeExceptionally(
            UnknownHostException(error.message).apply {
              initCause(error)
            },
          )
        }
      },
    )

    return result.get()
  }
}
