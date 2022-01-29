/*
 * Copyright (C) 2022 Square, Inc.
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

package okhttp3.internal.connection

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Implementation of HappyEyeballs Sorting Addresses.
 *
 * https://datatracker.ietf.org/doc/html/rfc8305#section-4
 */
object InetAddressOrder {
  // TODO consider return value a sequence
  // TODO consider making network aware
  // TODO make dns lookups async internally
  fun reorder(addresses: List<InetAddress>): List<InetAddress> {
    if (addresses.size < 2) {
      return addresses
    }

    val prioritised = priorityReorder(addresses)

    /*
     * If the client is stateful and has a history of expected round-trip
     * times (RTTs) for the routes to access each address, it SHOULD add a
     * Destination Address Selection rule between rules 8 and 9 that prefers
     * addresses with lower RTTs.  If the client keeps track of which
     * addresses it used in the past, it SHOULD add another Destination
     * Address Selection rule between the RTT rule and rule 9, which prefers
     * used addresses over unused ones.
     */

    val firstIpv6Index = prioritised.indexOfFirst {
      it is Inet6Address
    }
    val firstIpv4Index = prioritised.indexOfFirst {
      it is Inet4Address
    }

    if (firstIpv6Index == -1 || firstIpv4Index == -1) {
      return prioritised
    }

    return buildList {
      add(prioritised[firstIpv6Index])
      add(prioritised[firstIpv4Index])
      addAll(prioritised.filterIndexed { i, _ ->
        i != firstIpv6Index && i != firstIpv4Index
      })
    }
  }

  private fun priorityReorder(addresses: List<InetAddress>): List<InetAddress> {
    /*
     * TODO
     * Rule 1: Avoid unusable destinations.
     * Rule 2: Prefer matching scope.
     * Rule 3: Avoid deprecated addresses.
     * Rule 4: Prefer home addresses.
     * Rule 5: Prefer matching label.
     * Rule 6: Prefer higher precedence.
     * Rule 7: Prefer native transport.
     * Rule 8: Prefer smaller scope.
     * Rule 9: Use longest matching prefix.
     * Rule 10: Otherwise, leave the order unchanged.
     * Rules 9 and 10 MAY be superseded if the implementation has other means of sorting.
    */
    return addresses
  }
}
