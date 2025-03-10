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

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.net.InetAddress
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@Implements(DnsResolver::class)
class ShadowDnsResolver {
  var responder: (Request) -> Unit = {
    it.callback.onAnswer(listOf(), 0)
  }

  data class Request(
    val network: Network?,
    val domain: String,
    val nsType: Int,
    val flags: Int,
    val callback: DnsResolver.Callback<List<InetAddress>>,
  )

  @Implementation
  fun query(
    network: Network?,
    domain: String,
    nsType: Int,
    flags: Int,
    executor: Executor,
    cancellationSignal: CancellationSignal?,
    callback: DnsResolver.Callback<List<InetAddress>>,
  ) {
    responder(Request(network, domain, nsType, flags, callback))
  }

  companion object {
    @Implementation
    @JvmStatic
    fun getInstance(): DnsResolver = Shadow.newInstance(DnsResolver::class.java, arrayOf(), arrayOf())
  }
}
