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

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.tls.CertificateChainCleaner;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class CertificateChainCleanerTest {
  @Test public void equalsFromCertificate() {
    HeldCertificate rootA = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate rootB = new HeldCertificate.Builder()
        .serialNumber(2L)
        .build();
    assertThat(CertificateChainCleaner.Companion.get(rootB.certificate(), rootA.certificate()))
        .isEqualTo(CertificateChainCleaner.Companion.get(rootA.certificate(), rootB.certificate()));
  }

  @Test public void equalsFromTrustManager() {
    HandshakeCertificates handshakeCertificates = new HandshakeCertificates.Builder().build();
    X509TrustManager x509TrustManager = handshakeCertificates.trustManager();
    assertThat(CertificateChainCleaner.Companion.get(x509TrustManager)).isEqualTo(
        CertificateChainCleaner.Companion.get(x509TrustManager));
  }

  @Test public void normalizeSingleSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    assertThat(cleaner.clean(list(root), "hostname")).isEqualTo(list(root));
  }

  @Test public void normalizeUnknownSelfSignedCertificate() {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get();

    try {
      cleaner.clean(list(root), "hostname");
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void orderedChainOfCertificatesWithRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    assertThat(cleaner.clean(list(certB, certA, root), "hostname")).isEqualTo(
        list(certB, certA, root));
  }

  @Test public void orderedChainOfCertificatesWithoutRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    // Root is added!
    assertThat(cleaner.clean(list(certB, certA), "hostname")).isEqualTo(
        list(certB, certA, root));
  }

  @Test public void unorderedChainOfCertificatesWithRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build();
    HeldCertificate certC = new HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certB)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    assertThat(cleaner.clean(list(certC, certA, root, certB), "hostname")).isEqualTo(
        list(certC, certB, certA, root));
  }

  @Test public void unorderedChainOfCertificatesWithoutRoot() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build();
    HeldCertificate certC = new HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certB)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    assertThat(cleaner.clean(list(certC, certA, certB), "hostname")).isEqualTo(
        list(certC, certB, certA, root));
  }

  @Test public void unrelatedCertificatesAreOmitted() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build();
    HeldCertificate certUnnecessary = new HeldCertificate.Builder()
        .serialNumber(4L)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root.certificate());
    assertThat(cleaner.clean(list(certB, certUnnecessary, certA, root), "hostname")).isEqualTo(
        list(certB, certA, root));
  }

  @Test public void chainGoesAllTheWayToSelfSignedRoot() throws Exception {
    HeldCertificate selfSigned = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate trusted = new HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(selfSigned)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(trusted)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certA)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(
        selfSigned.certificate(), trusted.certificate());
    assertThat(cleaner.clean(list(certB, certA), "hostname")).isEqualTo(
        list(certB, certA, trusted, selfSigned));
    assertThat(cleaner.clean(list(certB, certA, trusted), "hostname")).isEqualTo(
        list(certB, certA, trusted, selfSigned));
    assertThat(cleaner.clean(list(certB, certA, trusted, selfSigned), "hostname")).isEqualTo(
        list(certB, certA, trusted, selfSigned));
  }

  @Test public void trustedRootNotSelfSigned() throws Exception {
    HeldCertificate unknownSigner = new HeldCertificate.Builder()
        .serialNumber(1L)
        .build();
    HeldCertificate trusted = new HeldCertificate.Builder()
        .signedBy(unknownSigner)
        .serialNumber(2L)
        .build();
    HeldCertificate intermediateCa = new HeldCertificate.Builder()
        .signedBy(trusted)
        .serialNumber(3L)
        .build();
    HeldCertificate certificate = new HeldCertificate.Builder()
        .signedBy(intermediateCa)
        .serialNumber(4L)
        .build();

    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(trusted.certificate());
    assertThat(cleaner.clean(list(certificate, intermediateCa), "hostname")).isEqualTo(
        list(certificate, intermediateCa, trusted));
    assertThat(cleaner.clean(list(certificate, intermediateCa, trusted), "hostname")).isEqualTo(
        list(certificate, intermediateCa, trusted));
  }

  @Test public void chainMaxLength() throws Exception {
    List<HeldCertificate> heldCertificates = chainOfLength(10);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate());
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate();
    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root);
    assertThat(cleaner.clean(certificates, "hostname")).isEqualTo(certificates);
    assertThat(cleaner.clean(certificates.subList(0, 9), "hostname")).isEqualTo(
        certificates);
  }

  @Test public void chainTooLong() {
    List<HeldCertificate> heldCertificates = chainOfLength(11);
    List<Certificate> certificates = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      certificates.add(heldCertificate.certificate());
    }

    X509Certificate root = heldCertificates.get(heldCertificates.size() - 1).certificate();
    CertificateChainCleaner cleaner = CertificateChainCleaner.Companion.get(root);
    try {
      cleaner.clean(certificates, "hostname");
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  /** Returns a chain starting at the leaf certificate and progressing to the root. */
  private List<HeldCertificate> chainOfLength(int length) {
    List<HeldCertificate> result = new ArrayList<>();
    for (int i = 1; i <= length; i++) {
      result.add(0, new HeldCertificate.Builder()
          .signedBy(!result.isEmpty() ? result.get(0) : null)
          .serialNumber(i)
          .build());
    }
    return result;
  }

  private List<Certificate> list(HeldCertificate... heldCertificates) {
    List<Certificate> result = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      result.add(heldCertificate.certificate());
    }
    return result;
  }
}
