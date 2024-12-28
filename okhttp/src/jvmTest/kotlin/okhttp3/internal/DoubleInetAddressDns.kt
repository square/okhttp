/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal

import java.net.InetAddress
import okhttp3.Dns

/**
 * A network that always resolves two IP addresses per host. Use this when testing route selection
 * fallbacks to guarantee that a fallback address is available.
 */
class DoubleInetAddressDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = Dns.SYSTEM.lookup(hostname)
    return listOf(addresses[0], addresses[0])
  }
}
