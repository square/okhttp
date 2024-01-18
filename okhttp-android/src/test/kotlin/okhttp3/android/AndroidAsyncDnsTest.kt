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

import assertk.assertFailure
import assertk.assertions.hasMessage
import assertk.assertions.isInstanceOf
import java.net.UnknownHostException
import okhttp3.AsyncDns
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@Config(shadows = [ShadowDnsResolver::class], sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AndroidAsyncDnsTest {
  @Test
  fun testDnsRequestInvalid() {
    val asyncDns = AndroidAsyncDns.IPv4
    val shadowDns: ShadowDnsResolver = Shadow.extract(asyncDns.resolver)

    shadowDns.responder = {
      throw IllegalArgumentException("Network.fromNetworkHandle refusing to instantiate NETID_UNSET Network.")
    }

    val dns = AsyncDns.toDns(asyncDns)

    assertFailure {
      dns.lookup("google.invalid")
    }.apply {
      hasMessage("Network.fromNetworkHandle refusing to instantiate NETID_UNSET Network.")
      isInstanceOf(UnknownHostException::class)
    }
  }
}
