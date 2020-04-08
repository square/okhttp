/*
 * Copyright (C) 2009 The Android Open Source Project
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
package okhttp3

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class FakeProxySelector : ProxySelector() {
  val proxies: MutableList<Proxy> = mutableListOf()

  fun addProxy(proxy: Proxy): FakeProxySelector {
    proxies.add(proxy)
    return this
  }

  override fun select(uri: URI): List<Proxy> {
    // Don't handle 'socket' schemes, which the RI's Socket class may request (for SOCKS).
    return if (uri.scheme == "http" || uri.scheme == "https") proxies else listOf(Proxy.NO_PROXY)
  }

  override fun connectFailed(
    uri: URI,
    sa: SocketAddress,
    ioe: IOException
  ) {
  }
}
