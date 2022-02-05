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

package okhttp3.internal.dns

import android.net.DnsResolver.TYPE_A
import android.net.DnsResolver.TYPE_AAAA
import java.net.InetAddress

interface AsyncDns {
  fun query(hostname: String, callback: Callback)

  interface Callback {
    fun onResults(dnsClass: DnsClass, addresses: List<InetAddress>)
    fun onComplete()
    fun onError(dnsClass: DnsClass, e: Exception)
  }

  enum class DnsClass(val type: Int) {

    IPV4(TYPE_A), IPV6(TYPE_AAAA);
  }
}
