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
package okhttp3.internal.tls;

import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * A certificate chain cleaner that uses a set of trusted root certificates to build the trusted
 * chain. This class duplicates the clean chain building performed during the TLS handshake. We
 * prefer other mechanisms where they exist, such as with
 * {@code okhttp3.internal.platform.AndroidPlatform.AndroidCertificateChainCleaner}.
 *
 * <p>This class includes code from <a href="https://conscrypt.org/">Conscrypt's</a> {@code
 * TrustManagerImpl} and {@code TrustedCertificateIndex}.
 */
public final class BasicCertificateChainCleaner extends CertificateChainCleaner {
  /** The maximum number of signers in a chain. We use 9 for consistency with OpenSSL. */
  private static final int MAX_SIGNERS = 9;

  private final TrustRootIndex trustRootIndex;

  public BasicCertificateChainCleaner(TrustRootIndex trustRootIndex) {
    this.trustRootIndex = trustRootIndex;
  }

  /**
   * Returns a cleaned chain for {@code chain}.
   *
   * <p>This method throws if the complete chain to a trusted CA certificate cannot be constructed.
   * This is unexpected unless the trust root index in this class has a different trust manager than
   * what was used to establish {@code chain}.
   */
  @Override public List<Certificate> clean(List<Certificate> chain, String hostname)
      throws SSLPeerUnverifiedException {
    Deque<Certificate> queue = new ArrayDeque<>(chain);
    List<Certificate> result = new ArrayList<>();
    result.add(queue.removeFirst());
    boolean foundTrustedCertificate = false;

    followIssuerChain:
    for (int c = 0; c < MAX_SIGNERS; c++) {
      X509Certificate toVerify = (X509Certificate) result.get(result.size() - 1);

      // If this cert has been signed by a trusted cert, use that. Add the trusted certificate to
      // the end of the chain unless it's already present. (That would happen if the first
      // certificate in the chain is itself a self-signed and trusted CA certificate.)
      X509Certificate trustedCert = trustRootIndex.findByIssuerAndSignature(toVerify);
      if (trustedCert != null) {
        if (result.size() > 1 || !toVerify.equals(trustedCert)) {
          result.add(trustedCert);
        }
        if (verifySignature(trustedCert, trustedCert)) {
          return result; // The self-signed cert is a root CA. We're done.
        }
        foundTrustedCertificate = true;
        continue;
      }

      // Search for the certificate in the chain that signed this certificate. This is typically
      // the next element in the chain, but it could be any element.
      for (Iterator<Certificate> i = queue.iterator(); i.hasNext(); ) {
        X509Certificate signingCert = (X509Certificate) i.next();
        if (verifySignature(toVerify, signingCert)) {
          i.remove();
          result.add(signingCert);
          continue followIssuerChain;
        }
      }

      // We've reached the end of the chain. If any cert in the chain is trusted, we're done.
      if (foundTrustedCertificate) {
        return result;
      }

      // The last link isn't trusted. Fail.
      throw new SSLPeerUnverifiedException(
          "Failed to find a trusted cert that signed " + toVerify);
    }

    throw new SSLPeerUnverifiedException("Certificate chain too long: " + result);
  }

  /** Returns true if {@code toVerify} was signed by {@code signingCert}'s public key. */
  private boolean verifySignature(X509Certificate toVerify, X509Certificate signingCert) {
    if (!toVerify.getIssuerDN().equals(signingCert.getSubjectDN())) return false;
    try {
      toVerify.verify(signingCert.getPublicKey());
      return true;
    } catch (GeneralSecurityException verifyFailed) {
      return false;
    }
  }
}
