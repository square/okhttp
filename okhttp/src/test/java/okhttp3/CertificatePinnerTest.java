/*
 * Copyright (C) 2014 Square, Inc.
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

import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class CertificatePinnerTest {
  static HeldCertificate certA1 = new HeldCertificate.Builder()
      .serialNumber(100L)
      .build();
  static String certA1Sha256Pin = CertificatePinner.pin(certA1.certificate());

  static HeldCertificate certB1 = new HeldCertificate.Builder()
      .serialNumber(200L)
      .build();
  static String certB1Sha256Pin = CertificatePinner.pin(certB1.certificate());

  static HeldCertificate certC1 = new HeldCertificate.Builder()
      .serialNumber(300L)
      .build();
  static String certC1Sha256Pin = CertificatePinner.pin(certC1.certificate());

  @Test public void malformedPin() throws Exception {
    CertificatePinner.Builder builder = new CertificatePinner.Builder();
    try {
      builder.add("example.com", "md5/DmxUShsZuNiqPQsX2Oi9uv2sCnw=");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void malformedBase64() throws Exception {
    CertificatePinner.Builder builder = new CertificatePinner.Builder();
    try {
      builder.add("example.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw*");
      fail();
    } catch (NullPointerException expected) {
    }
  }

  /** Multiple certificates generated from the same keypair have the same pin. */
  @Test public void sameKeypairSamePin() throws Exception {
    HeldCertificate heldCertificateA2 = new HeldCertificate.Builder()
        .keyPair(certA1.keyPair())
        .serialNumber(101L)
        .build();
    String keypairACertificate2Pin = CertificatePinner.pin(heldCertificateA2.certificate());

    HeldCertificate heldCertificateB2 = new HeldCertificate.Builder()
        .keyPair(certB1.keyPair())
        .serialNumber(201L)
        .build();
    String keypairBCertificate2Pin = CertificatePinner.pin(heldCertificateB2.certificate());

    assertThat(keypairACertificate2Pin).isEqualTo(certA1Sha256Pin);
    assertThat(keypairBCertificate2Pin).isEqualTo(certB1Sha256Pin);
    assertThat(certB1Sha256Pin).isNotEqualTo(certA1Sha256Pin);
  }

  @Test public void successfulCheck() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate());
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate(), certB1.certificate());
  }

  @Test public void unsuccessfulCheck() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build();

    try {
      certificatePinner.check("example.com", certB1.certificate());
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate());
    certificatePinner.check("example.com", certB1.certificate());
  }

  @Test public void multipleHostnamesForOneCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate());
    certificatePinner.check("www.example.com", certA1.certificate());
  }

  @Test public void absentHostnameMatches() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder().build();
    certificatePinner.check("example.com", certA1.certificate());
  }

  @Test public void successfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate());
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate(), certB1.certificate());
  }

  @Test public void unsuccessfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", certB1.certificate());
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate());
    certificatePinner.check("a.example.com", certB1.certificate());
  }

  @Test public void successfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate());
    certificatePinner.check("a.example.com", certB1.certificate());
  }

  @Test public void unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", certC1.certificate());
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }
}
