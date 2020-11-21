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

import java.util.HashSet;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static okhttp3.CertificatePinner.sha1Hash;
import static okio.ByteString.decodeBase64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
  static String certC1Sha1Pin = "sha1/" + sha1Hash(certC1.certificate()).base64();

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

    certificatePinner.check("example.com", singletonList(certA1.certificate()));
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificateOld() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", certA1.certificate(), certB1.certificate());
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("example.com", asList(certA1.certificate(), certB1.certificate()));
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

    certificatePinner.check("example.com", singletonList(certA1.certificate()));
    certificatePinner.check("example.com", singletonList(certB1.certificate()));
  }

  @Test public void multipleHostnamesForOneCertificate() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("example.com", singletonList(certA1.certificate()));
    certificatePinner.check("www.example.com", singletonList(certA1.certificate()));
  }

  @Test public void absentHostnameMatches() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder().build();
    certificatePinner.check("example.com", singletonList(certA1.certificate()));
  }

  @Test public void successfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", singletonList(certA1.certificate()));
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", asList(certA1.certificate(), certB1.certificate()));
  }

  @Test public void unsuccessfulCheckForWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", singletonList(certB1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneWildcardHostname() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", singletonList(certA1.certificate()));
    certificatePinner.check("a.example.com", singletonList(certB1.certificate()));
  }

  @Test public void successfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    certificatePinner.check("a.example.com", singletonList(certA1.certificate()));
    certificatePinner.check("a.example.com", singletonList(certB1.certificate()));
  }

  @Test public void unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate()
      throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", singletonList(certC1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void checkForHostnameWithDoubleAsterisk() throws Exception {
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add("**.example.co.uk", certA1Sha256Pin)
        .build();

    // Should be pinned:
    try {
      certificatePinner.check("example.co.uk", singletonList(certB1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
    try {
      certificatePinner.check("foo.example.co.uk", singletonList(certB1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
    try {
      certificatePinner.check("foo.bar.example.co.uk", singletonList(certB1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
    try {
      certificatePinner.check("foo.bar.baz.example.co.uk", singletonList(certB1.certificate()));
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }

    // Should not be pinned:
    certificatePinner.check("uk", singletonList(certB1.certificate()));
    certificatePinner.check("co.uk", singletonList(certB1.certificate()));
    certificatePinner.check("anotherexample.co.uk", singletonList(certB1.certificate()));
    certificatePinner.check("foo.anotherexample.co.uk", singletonList(certB1.certificate()));
  }

  @Test
  public void testBadPin() {
    try {
      new CertificatePinner.Pin("example.co.uk",
          "sha256/a");
      fail();
    } catch (IllegalArgumentException iae) {
      // expected
    }
  }

  @Test
  public void testBadAlgorithm() {
    try {
      new CertificatePinner.Pin("example.co.uk",
          "sha512/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
      fail();
    } catch (IllegalArgumentException iae) {
      // expected
    }
  }

  @Test
  public void testBadHost() {
    try {
      new CertificatePinner.Pin("example.*",
          "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
      fail();
    } catch (IllegalArgumentException iae) {
      // expected
    }
  }

  @Test
  public void testGoodPin() {
    CertificatePinner.Pin pin = new CertificatePinner.Pin("**.example.co.uk",
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    assertEquals(decodeBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), pin.getHash());
    assertEquals("sha256", pin.getHashAlgorithm());
    assertEquals("**.example.co.uk", pin.getPattern());

    assertTrue(pin.matchesHostname("www.example.co.uk"));
    assertTrue(pin.matchesHostname("gopher.example.co.uk"));
    assertFalse(pin.matchesHostname("www.example.com"));
  }

  @Test
  public void testMatchesSha256() {
    CertificatePinner.Pin pin = new CertificatePinner.Pin("example.com", certA1Sha256Pin);

    assertTrue(pin.matchesCertificate(certA1.certificate()));
    assertFalse(pin.matchesCertificate(certB1.certificate()));
  }

  @Test
  public void testMatchesSha1() {
    CertificatePinner.Pin pin = new CertificatePinner.Pin("example.com", certC1Sha1Pin);

    assertTrue(pin.matchesCertificate(certC1.certificate()));
    assertFalse(pin.matchesCertificate(certB1.certificate()));
  }

  @Test public void pinList() {
    CertificatePinner.Builder builder = new CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin);
    CertificatePinner certificatePinner = builder.build();

    List<CertificatePinner.Pin> expectedPins =
        asList(new CertificatePinner.Pin("example.com", certA1Sha256Pin),
            new CertificatePinner.Pin("www.example.com", certA1Sha256Pin));

    assertEquals(expectedPins, builder.getPins());
    assertEquals(new HashSet<>(expectedPins), certificatePinner.getPins());
  }
}
