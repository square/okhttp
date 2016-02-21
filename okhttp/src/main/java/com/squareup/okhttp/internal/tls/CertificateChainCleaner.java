/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.squareup.okhttp.internal.tls;

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
 * Computes the effective certificate chain from the raw array returned by Java's built in TLS APIs.
 * Cleaning a chain returns a list of certificates where the first element is {@code chain[0]}, each
 * certificate is signed by the certificate that follows, and the last certificate is a trusted CA
 * certificate.
 *
 * <p>Use of the chain cleaner is necessary to omit unexpected certificates that aren't relevant to
 * the TLS handshake and to extract the trusted CA certificate for the benefit of certificate
 * pinning.
 *
 * <p>This class includes code from <a href="https://conscrypt.org/">Conscrypt's</a> {@code
 * TrustManagerImpl} and {@code TrustedCertificateIndex}.
 */
public final class CertificateChainCleaner {
  private final TrustRootIndex trustRootIndex;

  public CertificateChainCleaner(TrustRootIndex trustRootIndex) {
    this.trustRootIndex = trustRootIndex;
  }

  /**
   * Returns a cleaned chain for {@code chain}.
   *
   * <p>This method throws if the complete chain to a trusted CA certificate cannot be constructed.
   * This is unexpected unless the trust root index in this class has a different trust manager than
   * what was used to establish {@code chain}.
   */
  public List<Certificate> clean(List<Certificate> chain) throws SSLPeerUnverifiedException {
    Deque<Certificate> queue = new ArrayDeque<>(chain);
    List<Certificate> result = new ArrayList<>();
    result.add(queue.removeFirst());

    followIssuerChain:
    while (true) {
      X509Certificate toVerify = (X509Certificate) result.get(result.size() - 1);

      // If this cert has been signed by a trusted cert, use that. If that's also a self-signed
      // cert, it's the root CA and we're done. Otherwise it might be a cached intermediate CA.
      // Add the trusted certificate to the end of the chain, unless it's already present. (That
      // would happen if the first certificate in the chain is itself a self-signed and trusted CA
      // certificate.)
      X509Certificate trustedCert = trustRootIndex.findByIssuerAndSignature(toVerify);
      if (trustedCert != null) {
        if (result.size() > 1 || !toVerify.equals(trustedCert)) {
          result.add(trustedCert);
        }
        if (verifySignature(trustedCert, trustedCert)) {
          return result; // The self-signed cert is the root CA. We're done.
        }
        continue; // Trusted cert, but not a root.
      }

      // Search for the certificate in the chain that signed this certificate. This is typically the
      // next element in the chain, but it could be any element.
      for (Iterator<Certificate> i = queue.iterator(); i.hasNext(); ) {
        X509Certificate signingCert = (X509Certificate) i.next();
        if (verifySignature(toVerify, signingCert)) {
          i.remove();
          result.add(signingCert);
          continue followIssuerChain;
        }
      }

      throw new SSLPeerUnverifiedException("Failed to find a cert that signed " + toVerify);
    }
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
