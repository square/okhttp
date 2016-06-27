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
import okhttp3.internal.tls.CertificateChainCleaner;
import okhttp3.internal.tls.HeldCertificate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class CertificateChainCleanerTest {
  @Test public void normalizeSingleSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(root), cleaner.clean(list(root), "hostname"));
  }

  @Test public void normalizeUnknownSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateChainCleaner cleaner = CertificateChainCleaner.get();

    try {
      cleaner.clean(list(root), "hostname");
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(certB, certA, root), cleaner.clean(list(certB, certA, root), "hostname"));
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(certB, certA, root),
        cleaner.clean(list(certB, certA), "hostname")); // Root is added!
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(certC, certB, certA, root),
        cleaner.clean(list(certC, certA, root, certB), "hostname"));
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(certC, certB, certA, root),
        cleaner.clean(list(certC, certA, certB), "hostname"));
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root.certificate);
    assertEquals(list(certB, certA, root),
        cleaner.clean(list(certB, certUnnecessary, certA, root), "hostname"));
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(
        selfSigned.certificate, trusted.certificate);
    assertEquals(list(certB, certA, trusted, selfSigned),
        cleaner.clean(list(certB, certA), "hostname"));
    assertEquals(list(certB, certA, trusted, selfSigned),
        cleaner.clean(list(certB, certA, trusted), "hostname"));
    assertEquals(list(certB, certA, trusted, selfSigned),
        cleaner.clean(list(certB, certA, trusted, selfSigned), "hostname"));
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

    CertificateChainCleaner cleaner = CertificateChainCleaner.get(trusted.certificate);
    assertEquals(list(certificate, intermediateCa, trusted),
        cleaner.clean(list(certificate, intermediateCa), "hostname"));
    assertEquals(list(certificate, intermediateCa, trusted),
        cleaner.clean(list(certificate, intermediateCa, trusted), "hostname"));
  }

  @Test public void chainMaxLength() throws Exception {
    List<HeldCertificate> heldCertificates = chainOfLength(10);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate);
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate;
    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root);
    assertEquals(certificates, cleaner.clean(certificates, "hostname"));
    assertEquals(certificates, cleaner.clean(certificates.subList(0, 9), "hostname"));
  }

  @Test public void chainTooLong() throws Exception {
    List<HeldCertificate> heldCertificates = chainOfLength(11);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate);
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate;
    CertificateChainCleaner cleaner = CertificateChainCleaner.get(root);
    try {
      cleaner.clean(certificates, "hostname");
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
