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
import javax.net.SocketFactory
import okhttp3.internal.Util
import okhttp3.internal.http.RecordingProxySelector
import org.junit.Test

import org.assertj.core.api.Assertions.assertThat

class AddressTest {
  private val dns = Dns.SYSTEM
  private val socketFactory = SocketFactory.getDefault()
  private val authenticator = Authenticator.NONE
  private val protocols = Util.immutableList(Protocol.HTTP_1_1)
  private val connectionSpecs = Util.immutableList(ConnectionSpec.MODERN_TLS)
  private val proxySelector = RecordingProxySelector()

  @Test
  @Throws(Exception::class)
  fun equalsAndHashcode() {
    val a = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, proxySelector)
    val b = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, proxySelector)
    assertThat(b).isEqualTo(a)
    assertThat(b.hashCode()).isEqualTo(a.hashCode())
  }

  @Test
  @Throws(Exception::class)
  fun differentProxySelectorsAreDifferent() {
    val a = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, RecordingProxySelector())
    val b = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, RecordingProxySelector())
    assertThat(b).isNotEqualTo(a)
  }

  @Test
  @Throws(Exception::class)
  fun addressToString() {
    val address = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, proxySelector)
    assertThat(address.toString()).isEqualTo(
        "Address{square.com:80, proxySelector=RecordingProxySelector}")
  }

  @Test
  @Throws(Exception::class)
  fun addressWithProxyToString() {
    val address = Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, Proxy.NO_PROXY, protocols, connectionSpecs, proxySelector)
    assertThat(address.toString()).isEqualTo(
        "Address{square.com:80, proxy=" + Proxy.NO_PROXY + "}")
  }
}
