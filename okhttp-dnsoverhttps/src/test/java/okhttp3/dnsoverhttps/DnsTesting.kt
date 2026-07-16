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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.Dns
import okio.IOException

sealed interface DnsEvent {
  data class Records(
    val last: Boolean,
    val records: List<Dns.Record>,
  ) : DnsEvent

  data class Failure(
    val e: IOException,
  ) : DnsEvent
}

internal fun Dns.Call.execute(): BlockingDeque<DnsEvent> {
  val result = LinkedBlockingDeque<DnsEvent>()

  enqueue(
    object : Dns.Callback {
      override fun onRecords(
        call: Dns.Call,
        last: Boolean,
        records: List<Dns.Record>,
      ) {
        result.put(DnsEvent.Records(last, records))
      }

      override fun onFailure(
        call: Dns.Call,
        e: IOException,
      ) {
        result.put(DnsEvent.Failure(e))
      }
    },
  )

  return result
}

/**
 * Use Burst to call either the blocking API or the non-blocking API, both with a signature that
 * resembles the [InetAddress.getAllByName] API.
 */
operator fun DnsOverHttps.invoke(
  entryPoint: EntryPoint,
  hostname: String,
): List<InetAddress> =
  when (entryPoint) {
    EntryPoint.Lookup -> {
      lookup(hostname)
    }

    EntryPoint.NewCall -> {
      buildList {
        val dnsEvents = newCall(Dns.Request(hostname)).execute()
        while (true) {
          when (val dnsEvent = dnsEvents.take()) {
            is DnsEvent.Failure -> {
              throw dnsEvent.e
            }

            is DnsEvent.Records -> {
              addAll(
                dnsEvent.records
                  .filterIsInstance<Dns.Record.IpAddress>()
                  .map { it.address },
              )

              if (dnsEvent.last) {
                if (isEmpty()) throw UnknownHostException("no results")
                break
              }
            }
          }
        }
      }
    }
  }

enum class EntryPoint {
  Lookup,
  NewCall,
}
