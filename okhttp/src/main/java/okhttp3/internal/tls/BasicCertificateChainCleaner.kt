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

import java.security.GeneralSecurityException
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.Deque
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * A certificate chain cleaner that uses a set of trusted root certificates to build the trusted
 * chain. This class duplicates the clean chain building performed during the TLS handshake. We
 * prefer other mechanisms where they exist, such as with
 * [okhttp3.internal.platform.AndroidPlatform.AndroidCertificateChainCleaner].
 *
 * This class includes code from <a href="https://conscrypt.org/">Conscrypt's</a> [TrustManagerImpl]
 * and [TrustedCertificateIndex].
 */
class BasicCertificateChainCleaner(
  private val trustRootIndex: TrustRootIndex
) : CertificateChainCleaner() {

  /**
   * Returns a cleaned chain for [chain].
   *
   * This method throws if the complete chain to a trusted CA certificate cannot be constructed.
   * This is unexpected unless the trust root index in this class has a different trust manager than
   * what was used to establish [chain].
   */
  @Throws(SSLPeerUnverifiedException::class)
  override fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
    val queue: Deque<Certificate> = ArrayDeque<Certificate>(chain)
    val result = mutableListOf<Certificate>()
    result.add(queue.removeFirst())
    var foundTrustedCertificate = false

    followIssuerChain@
    for (c in 0 until MAX_SIGNERS) {
      val toVerify = result[result.size - 1] as X509Certificate

      // If this cert has been signed by a trusted cert, use that. Add the trusted certificate to
      // the end of the chain unless it's already present. (That would happen if the first
      // certificate in the chain is itself a self-signed and trusted CA certificate.)
      val trustedCert = trustRootIndex.findByIssuerAndSignature(toVerify)
      if (trustedCert != null) {
        if (result.size > 1 || toVerify != trustedCert) {
          result.add(trustedCert)
        }
        if (verifySignature(trustedCert, trustedCert)) {
          return result // The self-signed cert is a root CA. We're done.
        }
        foundTrustedCertificate = true
        continue
      }

      // Search for the certificate in the chain that signed this certificate. This is typically
      // the next element in the chain, but it could be any element.
      val i = queue.iterator()
      while (i.hasNext()) {
        val signingCert = i.next() as X509Certificate
        if (verifySignature(toVerify, signingCert)) {
          i.remove()
          result.add(signingCert)
          continue@followIssuerChain
        }
      }

      // We've reached the end of the chain. If any cert in the chain is trusted, we're done.
      if (foundTrustedCertificate) {
        return result
      }

      // The last link isn't trusted. Fail.
      throw SSLPeerUnverifiedException(
          "Failed to find a trusted cert that signed $toVerify")
    }

    throw SSLPeerUnverifiedException("Certificate chain too long: $result")
  }

  /** Returns true if [toVerify] was signed by [signingCert]'s public key. */
  private fun verifySignature(toVerify: X509Certificate, signingCert: X509Certificate): Boolean {
    if (toVerify.issuerDN != signingCert.subjectDN) {
      return false
    }
    return try {
      toVerify.verify(signingCert.publicKey)
      true
    } catch (verifyFailed: GeneralSecurityException) {
      false
    }
  }

  override fun hashCode(): Int {
    return trustRootIndex.hashCode()
  }

  override fun equals(other: Any?): Boolean {
    return if (other === this) {
      true
    } else {
      other is BasicCertificateChainCleaner && other.trustRootIndex == trustRootIndex
    }
  }

  companion object {
    private const val MAX_SIGNERS = 9
  }
}
