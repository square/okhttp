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
package okhttp3

import java.net.Proxy
import java.net.ProxySelector
import javax.net.SocketFactory
import okhttp3.internal.http.RecordingProxySelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AddressTest {
  private val dns = Dns.SYSTEM
  private val socketFactory = SocketFactory.getDefault()
  private val authenticator = Authenticator.NONE
  private val protocols = listOf(Protocol.HTTP_1_1)
  private val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS)
  private val proxySelector = RecordingProxySelector()

  @Test fun equalsAndHashcode() {
    val a = testAddress()
    val b = testAddress()
    assertThat(b).isEqualTo(a)
    assertThat(b.hashCode()).isEqualTo(a.hashCode())
  }

  @Test fun differentProxySelectorsAreDifferent() {
    val a = testAddress(proxySelector = RecordingProxySelector())
    val b = testAddress(proxySelector = RecordingProxySelector())
    assertThat(b).isNotEqualTo(a)
  }

  @Test fun addressToString() {
    val address = testAddress()
    assertThat(address.toString())
      .isEqualTo("Address{square.com:80, proxySelector=RecordingProxySelector}")
  }

  @Test fun addressWithProxyToString() {
    val address = testAddress(proxy = Proxy.NO_PROXY)
    assertThat(address.toString())
      .isEqualTo("Address{square.com:80, proxy=${Proxy.NO_PROXY}}")
  }

  private fun testAddress(
    proxy: Proxy? = null,
    proxySelector: ProxySelector = this.proxySelector
  ) = Address(
    uriHost = "square.com",
    uriPort = 80,
    dns = dns,
    socketFactory = socketFactory,
    sslSocketFactory = null,
    hostnameVerifier = null,
    certificatePinner = null,
    proxyAuthenticator = authenticator,
    proxy = proxy,
    protocols = protocols,
    connectionSpecs = connectionSpecs,
    proxySelector = proxySelector
  )
}
