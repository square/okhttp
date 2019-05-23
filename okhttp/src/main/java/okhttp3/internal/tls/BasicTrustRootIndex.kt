/*
 * Copyright (C) 2016 Square, Inc.
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

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** A simple index that of trusted root certificates that have been loaded into memory. */
class BasicTrustRootIndex(vararg caCerts: X509Certificate) : TrustRootIndex {
  private val subjectToCaCerts: Map<X500Principal, Set<X509Certificate>>

  init {
    val map = mutableMapOf<X500Principal, MutableSet<X509Certificate>>()
    for (caCert in caCerts) {
      map.getOrPut(caCert.subjectX500Principal) { mutableSetOf() }.add(caCert)
    }
    this.subjectToCaCerts = map
  }

  override fun findByIssuerAndSignature(cert: X509Certificate): X509Certificate? {
    val issuer = cert.issuerX500Principal
    val subjectCaCerts = subjectToCaCerts[issuer] ?: return null

    return subjectCaCerts.firstOrNull {
      try {
        cert.verify(it.publicKey)
        return@firstOrNull true
      } catch (_: Exception) {
        return@firstOrNull false
      }
    }
  }

  override fun equals(other: Any?): Boolean {
    return other === this ||
        (other is BasicTrustRootIndex && other.subjectToCaCerts == subjectToCaCerts)
  }

  override fun hashCode(): Int {
    return subjectToCaCerts.hashCode()
  }
}
