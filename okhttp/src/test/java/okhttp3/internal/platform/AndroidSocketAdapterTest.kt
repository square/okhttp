/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.platform

import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import org.conscrypt.Conscrypt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

@RunWith(Parameterized::class)
class AndroidSocketAdapterTest(val adapter: AndroidSocketAdapter) {
  val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
  val context = SSLContext.getInstance("TLS", provider)
  init {
    context.init(null, null, null)
  }

  @Test
  fun testInit() {
    val socketFactory = context.socketFactory
    adapter.matchesSocketFactory(socketFactory)

    val sslSocket = socketFactory.createSocket() as SSLSocket
    adapter.matchesSocket(sslSocket)

    // Could avoid completely for ConscryptSocketAdapter
    assertNotNull(adapter.sslSocketClass)
    assertNotNull(adapter.paramClass)
    assertNotNull(adapter.getAlpnSelectedProtocol)
    assertNotNull(adapter.setAlpnProtocols)
    assertNotNull(adapter.setHostname)
    assertNotNull(adapter.setUseSessionTickets)

    adapter.configureTlsExtensions(sslSocket, "example.com", listOf(HTTP_2, HTTP_1_1))
    // not connected
    assertNull(adapter.getSelectedProtocol(sslSocket))
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<AndroidSocketAdapter> {
      return listOf(ConscryptSocketAdapter, AndroidSocketAdapter("org.conscrypt"));
    }
  }
}
