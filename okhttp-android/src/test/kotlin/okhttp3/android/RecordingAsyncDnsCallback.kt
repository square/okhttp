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
package okhttp3.android

import java.net.InetAddress
import java.util.Collections
import okhttp3.android.internal.AsyncDns
import okio.IOException

class RecordingAsyncDnsCallback : AsyncDns.Callback {
  val events = Collections.synchronizedList(mutableListOf<Event>())

  override fun onAddresses(
    hasMore: Boolean,
    hostname: String,
    addresses: List<InetAddress>,
  ) {
    add(Event.Addresses(hasMore, hostname, addresses))
  }

  override fun onFailure(
    hasMore: Boolean,
    hostname: String,
    e: IOException,
  ) {
    add(Event.Failure(hasMore, hostname, e))
  }

  private fun add(event: Event) {
    synchronized(events) {
      events.add(event)
      (events as Object).notifyAll()
    }
  }

  fun awaitCompletion() {
    synchronized(events) {
      while (events.find { !it.hasMore } == null) {
        (events as Object).wait()
      }
    }
  }

  sealed interface Event {
    val hasMore: Boolean

    data class Addresses(
      override val hasMore: Boolean,
      val hostname: String,
      val addresses: List<InetAddress>,
    ) : Event

    data class Failure(
      override val hasMore: Boolean,
      val hostname: String,
      val e: IOException,
    ) : Event
  }
}
