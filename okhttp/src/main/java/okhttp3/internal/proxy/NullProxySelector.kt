/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.internal.proxy

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A proxy selector that always returns the [Proxy.NO_PROXY].
 */
object NullProxySelector : ProxySelector() {
  override fun select(uri: URI?): List<Proxy> {
    requireNotNull(uri) { "uri must not be null" }
    return listOf(Proxy.NO_PROXY)
  }

  override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
  }
}
