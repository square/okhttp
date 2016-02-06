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
package com.squareup.okhttp.internal.tls;

import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import com.squareup.okhttp.internal.HeldCertificate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class CertificateAuthorityCouncilTest {
  @Test public void normalizeSingleSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(root), council.normalizeCertificateChain(list(root)));
  }

  @Test public void normalizeUnknownSelfSignedCertificate() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil();

    try {
      council.normalizeCertificateChain(list(root));
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

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(certB, certA, root),
        council.normalizeCertificateChain(list(certB, certA, root)));
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

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(certB, certA, root),
        council.normalizeCertificateChain(list(certB, certA))); // Root is added!
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

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(certC, certB, certA, root),
        council.normalizeCertificateChain(list(certC, certA, root, certB)));
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

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(certC, certB, certA, root),
        council.normalizeCertificateChain(list(certC, certA, certB)));
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

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(root.certificate);
    assertEquals(list(certB, certA, root),
        council.normalizeCertificateChain(list(certB, certUnnecessary, certA, root)));
  }

  @Test public void unnecessaryTrustedCertificatesAreOmitted() throws Exception {
    HeldCertificate superRoot = new HeldCertificate.Builder()
        .serialNumber("1")
        .build();
    HeldCertificate root = new HeldCertificate.Builder()
        .serialNumber("2")
        .issuedBy(superRoot)
        .build();
    HeldCertificate certA = new HeldCertificate.Builder()
        .serialNumber("3")
        .issuedBy(root)
        .build();
    HeldCertificate certB = new HeldCertificate.Builder()
        .serialNumber("4")
        .issuedBy(certA)
        .build();

    CertificateAuthorityCouncil council = new CertificateAuthorityCouncil(
        superRoot.certificate, root.certificate);
    assertEquals(list(certB, certA, root),
        council.normalizeCertificateChain(list(certB, certA, root, superRoot)));
  }

  private List<Certificate> list(HeldCertificate... heldCertificates) {
    List<Certificate> result = new ArrayList<>();
    for (HeldCertificate heldCertificate : heldCertificates) {
      result.add(heldCertificate.certificate);
    }
    return result;
  }
}
