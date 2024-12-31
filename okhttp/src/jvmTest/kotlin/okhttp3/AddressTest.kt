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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import java.net.Proxy
import okhttp3.internal.http.RecordingProxySelector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AddressTest {
  private val factory =
    TestValueFactory().apply {
      uriHost = "example.com"
      uriPort = 80
    }

  @AfterEach fun tearDown() {
    factory.close()
  }

  @Test fun equalsAndHashcode() {
    val a = factory.newAddress()
    val b = factory.newAddress()
    assertThat(b).isEqualTo(a)
    assertThat(b.hashCode()).isEqualTo(a.hashCode())
  }

  @Test fun differentProxySelectorsAreDifferent() {
    val a = factory.newAddress(proxySelector = RecordingProxySelector())
    val b = factory.newAddress(proxySelector = RecordingProxySelector())
    assertThat(b).isNotEqualTo(a)
  }

  @Test fun addressToString() {
    val address = factory.newAddress()
    assertThat(address.toString())
      .isEqualTo("Address{example.com:80, proxySelector=RecordingProxySelector}")
  }

  @Test fun addressWithProxyToString() {
    val address = factory.newAddress(proxy = Proxy.NO_PROXY)
    assertThat(address.toString())
      .isEqualTo("Address{example.com:80, proxy=${Proxy.NO_PROXY}}")
  }
}
