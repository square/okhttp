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
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package okhttp3.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import okhttp3.OkHttpClient
import okhttp3.android.NetworkSelection.withNetwork
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@Config(shadows = [ShadowDnsResolver::class], sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkSelectionTest {
  val network1 = ShadowNetwork.newInstance(1)
  val network2 = ShadowNetwork.newInstance(2)

  @Test
  fun testEquality() {
    val defaultClient =
      OkHttpClient.Builder()
        .withNetwork(network = null)
        .build()

    val network1Client =
      defaultClient.newBuilder()
        .withNetwork(network = network1)
        .build()

    assertThat(network1Client.dns).isNotEqualTo(defaultClient.dns)
    assertThat(network1Client.socketFactory).isNotEqualTo(defaultClient.socketFactory)

    val network2Client =
      network1Client.newBuilder()
        .withNetwork(network = network2)
        .build()

    assertThat(network2Client.socketFactory).isNotEqualTo(network1Client.socketFactory)

    val defaultClient2 =
      network2Client.newBuilder()
        .withNetwork(network = null)
        .build()

    assertThat(defaultClient2.socketFactory).isNotEqualTo(network2Client.socketFactory)
    assertThat(defaultClient2.socketFactory).isEqualTo(defaultClient.socketFactory)

    val network1Client2 =
      network2Client.newBuilder()
        .withNetwork(network = network1)
        .build()

    assertThat(network1Client2.socketFactory).isEqualTo(network1Client.socketFactory)
  }

  @Test
  fun testNotTaggedOnDefault() {
    val defaultClient =
      OkHttpClient.Builder()
        .withNetwork(network = null)
        .build()

    val network1Client =
      defaultClient.newBuilder()
        .withNetwork(network = network1)
        .build()
  }

  @Test
  fun testTaggedOnSpecific() {
    val network1Client =
      OkHttpClient.Builder()
        .withNetwork(network = network1)
        .build()
  }
}
