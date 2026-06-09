/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.internal.platform.android

import android.security.NetworkSecurityPolicy
import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.ech.EchMode
import org.junit.Test

class AndroidEchModeConfigurationTest {
  @Test
  fun mapsNetworkSecurityPolicyModes() {
    assertThat(
      EchMode.fromNetworkSecurityPolicy(NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC),
    ).isEqualTo(EchMode.Opportunistic)
    assertThat(
      EchMode.fromNetworkSecurityPolicy(NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED),
    ).isEqualTo(EchMode.Strict)
    assertThat(
      EchMode.fromNetworkSecurityPolicy(NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_DISABLED),
    ).isEqualTo(EchMode.Disabled)
    assertThat(EchMode.fromNetworkSecurityPolicy(-1)).isEqualTo(EchMode.Unspecified)
  }
}
