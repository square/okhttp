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
package okhttp3;

import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.internal.HeldCertificate;
import okhttp3.internal.tls.CertificateChainCleaner;
import okhttp3.internal.tls.RealTrustRootIndex;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class CertificateChainCleanerTest {
  @Test public void normalizeSingleSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(root), council.clean(list(root)));
  }

  @Test public void normalizeUnknownSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateChainCleaner council = new CertificateChainCleaner(new RealTrustRootIndex());

    try {
      council.clean(list(root));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void orderedChainOfCertificatesWithRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(certA)
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(certB, certA, root), council.clean(list(certB, certA, root)));
  }

  @Test public void orderedChainOfCertificatesWithoutRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(certA)
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(certB, certA, root), council.clean(list(certB, certA))); // Root is added!
  }

  @Test public void unorderedChainOfCertificatesWithRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(certA)
        .build();
    HeldCertificate certC = new HeldCertificate.Builder()
        .serialNumber("4")
        .issuedBy(certB)
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(certC, certB, certA, root), council.clean(list(certC, certA, root, certB)));
  }

  @Test public void unorderedChainOfCertificatesWithoutRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(certA)
        .build();
    HeldCertificate certC = new HeldCertificate.Builder()
        .serialNumber("4")
        .issuedBy(certB)
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(certC, certB, certA, root), council.clean(list(certC, certA, certB)));
  }

  @Test public void unrelatedCertificatesAreOmitted() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(certA)
        .build();
    HeldCertificate certUnnecessary = new HeldCertificate.Builder()
        .serialNumber("4")
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(root.certificate));
    assertEquals(list(certB, certA, root),
        council.clean(list(certB, certUnnecessary, certA, root)));
  }

  @Test public void chainGoesAllTheWayToSelfSignedRoot() throws Exception {
    HeldCertificate selfSigned = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate trusted = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(selfSigned)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(trusted)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("4")
        .issuedBy(certA)
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(selfSigned.certificate, trusted.certificate));
    assertEquals(list(certB, certA, trusted, selfSigned),
        council.clean(list(certB, certA)));
    assertEquals(list(certB, certA, trusted, selfSigned),
        council.clean(list(certB, certA, trusted)));
    assertEquals(list(certB, certA, trusted, selfSigned),
        council.clean(list(certB, certA, trusted, selfSigned)));
  }

  @Test public void trustedRootNotSelfSigned() throws Exception {
    HeldCertificate unknownSigner = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate trusted = new HeldCertificate.Builder()
        .issuedBy(unknownSigner)
        .serialNumber("2")
        .build();
    HeldCertificate intermediateCa = new HeldCertificate.Builder()
        .issuedBy(trusted)
        .serialNumber("3")
        .build();
    HeldCertificate certificate = new HeldCertificate.Builder()
        .issuedBy(intermediateCa)
        .serialNumber("4")
        .build();

    CertificateChainCleaner council = new CertificateChainCleaner(
        new RealTrustRootIndex(trusted.certificate));
    assertEquals(list(certificate, intermediateCa, trusted),
        council.clean(list(certificate, intermediateCa)));
    assertEquals(list(certificate, intermediateCa, trusted),
        council.clean(list(certificate, intermediateCa, trusted)));
  }

  @Test public void chainMaxLength() throws Exception {
    List<HeldCertificate> heldCertificates = chainOfLength(10);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate);
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate;
    CertificateChainCleaner council = new CertificateChainCleaner(new RealTrustRootIndex(root));
    assertEquals(certificates, council.clean(certificates));
    assertEquals(certificates, council.clean(certificates.subList(0, 9)));
  }

  @Test public void chainTooLong() throws Exception {
    List<HeldCertificate> heldCertificates = chainOfLength(11);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate);
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate;
    CertificateChainCleaner council = new CertificateChainCleaner(new RealTrustRootIndex(root));
    try {
      council.clean(certificates);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  /** Returns a chain starting at the leaf certificate and progressing to the root. */
  private List<HeldCertificate> chainOfLength(int length) throws GeneralSecurityException {
    List<HeldCertificate> result = new ArrayList<>();
    for (int i = 1; i <= length; i++) {
      result.add(0, new HeldCertificate.Builder()
          .issuedBy(!result.isEmpty() ? result.get(0) : null)
          .serialNumber(Integer.toString(i))
          .build());
    }
    return result;
  }

  private List<Certificate> list(HeldCertificate... heldCertificates) {
    List<Certificate> result = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      result.add(heldCertificate.certificate);
    }
    return result;
  }
}
