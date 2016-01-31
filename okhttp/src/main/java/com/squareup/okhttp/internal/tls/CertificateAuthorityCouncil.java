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
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;

/**
 * A set of trusted Certificate Authority (CA) certificates that are trusted to verify the TLS
 * certificates offered by remote web servers.
 *
 * <p>This class includes code from <a href="https://conscrypt.org/">Conscrypt's</a> {@code
 * TrustManagerImpl} and {@code TrustedCertificateIndex}.
 */
public final class CertificateAuthorityCouncil {
  private final Map<X500Principal, List<X509Certificate>> subjectToCaCerts = new LinkedHashMap<>();

  public CertificateAuthorityCouncil(X509Certificate... caCerts) {
    for (X509Certificate caCert : caCerts) {
      X500Principal subject = caCert.getSubjectX500Principal();
      List<X509Certificate> subjectCaCerts = subjectToCaCerts.get(subject);
      if (subjectCaCerts == null) {
        subjectCaCerts = new ArrayList<>(1);
        subjectToCaCerts.put(subject, subjectCaCerts);
      }
      subjectCaCerts.add(caCert);
    }
  }

  /**
   * Computes the effective certificate chain from the raw array returned by Java's built in TLS
   * APIs. This method returns a list of certificates where the first element is {@code chain[0]},
   * each certificate is signed by the certificate that follows, and the last certificate is a
   * trusted CA certificate.
   *
   * <p>Use of this method is necessary to omit unexpected certificates that aren't relevant to the
   * TLS handshake and to extract the trusted CA certificate for the benefit of certificate pinning.
   *
   * <p>This method throws if the complete chain to a trusted CA certificate cannot be constructed.
   * This is unexpected unless the X509 trust manager in this class is different from the trust
   * manager that was used to establish {@code chain}.
   */
  public List<Certificate> normalizeCertificateChain(List<Certificate> chain)
      throws SSLPeerUnverifiedException {
    Deque<Certificate> queue = new ArrayDeque<>(chain);
    List<Certificate> result = new ArrayList<>();
    result.add(queue.removeFirst());

    followIssuerChain:
    while (true) {
      X509Certificate toVerify = (X509Certificate) result.get(result.size() - 1);

      // If this cert has been signed by a trusted CA cert, we're done. Add the trusted CA
      // certificate to the end of the chain, unless it's already present. (That would happen if the
      // first certificate in the chain is itself a self-signed and trusted CA certificate.)
      X509Certificate caCert = findByIssuerAndSignature(toVerify);
      if (caCert != null && verifySignature(toVerify, caCert)) {
        if (result.size() > 1 || !toVerify.equals(caCert)) {
          result.add(caCert);
        }
        return result;
      }

      // Search for the certificate in the chain that signed this certificate. This is typically the
      // next element in the chain, but it could be any element.
      for (Iterator<Certificate> i = queue.iterator(); i.hasNext(); ) {
        X509Certificate signingCert = (X509Certificate) i.next();
        if (toVerify.getIssuerDN().equals(signingCert.getSubjectDN())
            && verifySignature(toVerify, signingCert)) {
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
    try {
      toVerify.verify(signingCert.getPublicKey());
      return true;
    } catch (GeneralSecurityException verifyFailed) {
      return false;
    }
  }

  /** Returns the trusted CA certificate that signed {@code cert}. */
  private X509Certificate findByIssuerAndSignature(X509Certificate cert) {
    X500Principal issuer = cert.getIssuerX500Principal();
    List<X509Certificate> subjectCaCerts = subjectToCaCerts.get(issuer);
    if (subjectCaCerts == null) return null;

    for (X509Certificate caCert : subjectCaCerts) {
      PublicKey publicKey = caCert.getPublicKey();
      try {
        cert.verify(publicKey);
        return caCert;
      } catch (Exception ignored) {
      }
    }

    return null;
  }
}
