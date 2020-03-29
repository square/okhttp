/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.internal.tls

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

class HostnameVerifierOverride(val default: HostnameVerifier, val overrides: List<TrustManagerOverride>) : HostnameVerifier {
  override fun verify(hostName: String, session: SSLSession): Boolean {
    val verifier = overrides.find {
      it.predicate(hostName) && it.hostnameVerifier != null
    }?.hostnameVerifier ?: default

    return verifier.verify(hostName, session)
  }
}
