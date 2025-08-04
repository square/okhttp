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

package okhttp3.internal.http

import assertk.assertThat
import assertk.assertions.containsExactly
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.internal.format

class RecordingProxySelector : ProxySelector() {
  @JvmField val proxies = mutableListOf<Proxy>()

  val requestedUris = mutableListOf<URI>()
  val failures = mutableListOf<String>()

  override fun select(uri: URI): List<Proxy> {
    requestedUris.add(uri)
    return proxies
  }

  fun assertRequests(vararg expectedUris: URI?) {
    assertThat(requestedUris).containsExactly(*expectedUris)
    requestedUris.clear()
  }

  override fun connectFailed(
    uri: URI,
    sa: SocketAddress,
    ioe: IOException,
  ) {
    val socketAddress = sa as InetSocketAddress
    failures.add(format("%s %s:%d %s", uri, socketAddress, socketAddress.port, ioe.message!!))
  }

  override fun toString() = "RecordingProxySelector"
}
