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

import javax.security.auth.x500.X500Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A simple index that of trusted root certificates that have been loaded into memory. */
public final class BasicTrustRootIndex implements TrustRootIndex {
  private final Map<X500Principal, Set<X509Certificate>> subjectToCaCerts;

  public BasicTrustRootIndex(X509Certificate... caCerts) {
    subjectToCaCerts = new LinkedHashMap<>();
    for (X509Certificate caCert : caCerts) {
      X500Principal subject = caCert.getSubjectX500Principal();
      Set<X509Certificate> subjectCaCerts = subjectToCaCerts.get(subject);
      if (subjectCaCerts == null) {
        subjectCaCerts = new LinkedHashSet<>(1);
        subjectToCaCerts.put(subject, subjectCaCerts);
      }
      subjectCaCerts.add(caCert);
    }
  }

  @Override public X509Certificate findByIssuerAndSignature(X509Certificate cert) {
    X500Principal issuer = cert.getIssuerX500Principal();
    Set<X509Certificate> subjectCaCerts = subjectToCaCerts.get(issuer);
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

  @Override public boolean equals(Object other) {
    if (other == this) return true;
    return other instanceof okhttp3.internal.tls.BasicTrustRootIndex
        && ((okhttp3.internal.tls.BasicTrustRootIndex) other).subjectToCaCerts.equals(subjectToCaCerts);
  }

  @Override public int hashCode() {
    return subjectToCaCerts.hashCode();
  }
}
