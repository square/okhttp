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

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.CertificatePinner.Pin;
import okhttp3.internal.HeldCertificate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CertificatePinnerTest {
  static HeldCertificate certA1;
  static String certA1Sha256Pin;

  static HeldCertificate certB1;
  static String certB1Sha256Pin;

  static HeldCertificate certC1;
  static String certC1Sha256Pin;

  static {
    try {
      certA1 = new HeldCertificate.Builder()
          .serialNumber("100")
          .build();
      certA1Sha256Pin = "sha256/" + CertificatePinner.sha256(certA1.certificate).base64();

      certB1 = new HeldCertificate.Builder()
          .serialNumber("200")
          .build();
      certB1Sha256Pin = "sha256/" + CertificatePinner.sha256(certB1.certificate).base64();

      certC1 = new HeldCertificate.Builder()
          .serialNumber("300")
          .build();
      certC1Sha256Pin = "sha256/" + CertificatePinner.sha256(certC1.certificate).base64();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

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
    } catch (IllegalArgumentException expected) {
    }
  }

  /** Multiple certificates generated from the same keypair have the same pin. */
  @Test public void sameKeypairSamePin() throws Exception {
    HeldCertificate heldCertificateA2 = new HeldCertificate.Builder()
        .keyPair(certA1.keyPair)
        .serialNumber("101")
        .build();
    String keypairACertificate2Pin = CertificatePinner.pin(heldCertificateA2.certificate);

    HeldCertificate heldCertificateB2 = new HeldCertificate.Builder()
        .keyPair(certB1.keyPair)
        .serialNumber("201")
        .build();
    String keypairBCertificate2Pin = CertificatePinner.pin(heldCertificateB2.certificate);

    assertTrue(certA1Sha256Pin.equals(keypairACertificate2Pin));
    assertTrue(certB1Sha256Pin.equals(keypairBCertificate2Pin));
    assertFalse(certA1Sha256Pin.equals(certB1Sha256Pin));
  }

  @Test public void successfulCheck() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate);
  }

  @Test public void successfulCheckSha1Pin() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", "sha1/" + CertificatePinner.sha1(certA1.certificate).base64())
        .build();

    certificatePinner.check("example.com", certA1.certificate);
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate, certB1.certificate);
  }

  @Test public void unsuccessfulCheck() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build();

    try {
      certificatePinner.check("example.com", certB1.certificate);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate);
    certificatePinner.check("example.com", certB1.certificate);
  }

  @Test public void multipleHostnamesForOneCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate);
    certificatePinner.check("www.example.com", certA1.certificate);
  }

  @Test public void absentHostnameMatches() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder().build();
    certificatePinner.check("example.com", certA1.certificate);
  }

  @Test public void successfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate);
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate, certB1.certificate);
  }

  @Test public void unsuccessfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", certB1.certificate);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate);
    certificatePinner.check("a.example.com", certB1.certificate);
  }

  @Test public void successfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", certA1.certificate);
    certificatePinner.check("a.example.com", certB1.certificate);
  }

  @Test public void unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", certC1.certificate);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void successfulFindMatchingPins() {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("first.com", certA1Sha256Pin, certB1Sha256Pin)
        .add("second.com", certC1Sha256Pin)
        .build();

    List<Pin> expectedPins = Arrays.asList(
        new Pin("first.com", certA1Sha256Pin),
        new Pin("first.com", certB1Sha256Pin));
    assertEquals(expectedPins, certificatePinner.findMatchingPins("first.com"));
  }

  @Test public void successfulFindMatchingPinsForWildcardAndDirectCertificates() {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .add("b.example.com", certC1Sha256Pin)
        .build();

    List<Pin> expectedPins = Arrays.asList(
        new Pin("*.example.com", certA1Sha256Pin),
        new Pin("a.example.com", certB1Sha256Pin));
    assertEquals(expectedPins, certificatePinner.findMatchingPins("a.example.com"));
  }

  @Test public void wildcardHostnameShouldNotMatchThroughDot() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    assertEquals(Collections.emptyList(), certificatePinner.findMatchingPins("example.com"));
    assertEquals(Collections.emptyList(), certificatePinner.findMatchingPins("a.b.example.com"));
  }
}
