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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.SslContextBuilder;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import okio.ByteString;
import org.junit.Test;

import static com.squareup.okhttp.TestUtil.setOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class Sha1CertificatePinnerTest {
  static SslContextBuilder sslContextBuilder;

  static KeyPair keyPairA;
  static X509Certificate keypairACertificate1;
  static String keypairACertificate1Pin;
  static ByteString keypairACertificate1PinBase64;

  static KeyPair keyPairB;
  static X509Certificate keypairBCertificate1;
  static String keypairBCertificate1Pin;
  static ByteString keypairBCertificate1PinBase64;

  static KeyPair keyPairC;
  static X509Certificate keypairCCertificate1;
  static String keypairCCertificate1Pin;

  static {
    try {
      sslContextBuilder = new SslContextBuilder("example.com");

      keyPairA = sslContextBuilder.generateKeyPair();
      keypairACertificate1 = sslContextBuilder.selfSignedCertificate(keyPairA, "1");
      keypairACertificate1Pin = Sha1CertificatePinner.pin(keypairACertificate1);
      keypairACertificate1PinBase64 = pinToBase64(keypairACertificate1Pin);

      keyPairB = sslContextBuilder.generateKeyPair();
      keypairBCertificate1 = sslContextBuilder.selfSignedCertificate(keyPairB, "1");
      keypairBCertificate1Pin = Sha1CertificatePinner.pin(keypairBCertificate1);
      keypairBCertificate1PinBase64 = pinToBase64(keypairBCertificate1Pin);

      keyPairC = sslContextBuilder.generateKeyPair();
      keypairCCertificate1 = sslContextBuilder.selfSignedCertificate(keyPairC, "1");
      keypairCCertificate1Pin = Sha1CertificatePinner.pin(keypairCCertificate1);
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

  static ByteString pinToBase64(String pin) {
    return ByteString.decodeBase64(pin.substring("sha1/".length()));
  }

  @Test public void malformedPin() throws Exception {
    Sha1CertificatePinner.Builder builder = new Sha1CertificatePinner.Builder();
    try {
      builder.add("example.com", "md5/DmxUShsZuNiqPQsX2Oi9uv2sCnw=");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void malformedBase64() throws Exception {
    Sha1CertificatePinner.Builder builder = new Sha1CertificatePinner.Builder();
    try {
      builder.add("example.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw*");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  /** Multiple certificates generated from the same keypair have the same pin. */
  @Test public void sameKeypairSamePin() throws Exception {
    X509Certificate keypairACertificate2 = sslContextBuilder.selfSignedCertificate(keyPairA, "2");
    String keypairACertificate2Pin = Sha1CertificatePinner.pin(keypairACertificate2);

    X509Certificate keypairBCertificate2 = sslContextBuilder.selfSignedCertificate(keyPairB, "2");
    String keypairBCertificate2Pin = Sha1CertificatePinner.pin(keypairBCertificate2);

    assertTrue(keypairACertificate1Pin.equals(keypairACertificate2Pin));
    assertTrue(keypairBCertificate1Pin.equals(keypairBCertificate2Pin));
    assertFalse(keypairACertificate1Pin.equals(keypairBCertificate1Pin));
  }

  @Test public void successfulCheck() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("example.com", keypairACertificate1Pin)
        .build();

    certificatePinner.check("example.com", keypairACertificate1);
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificate() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("example.com", keypairBCertificate1Pin)
        .build();

    certificatePinner.check("example.com", keypairACertificate1, keypairBCertificate1);
  }

  @Test public void unsuccessfulCheck() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("example.com", keypairACertificate1Pin)
        .build();

    try {
      certificatePinner.check("example.com", keypairBCertificate1);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneHostname() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("example.com", keypairACertificate1Pin, keypairBCertificate1Pin)
        .build();

    certificatePinner.check("example.com", keypairACertificate1);
    certificatePinner.check("example.com", keypairBCertificate1);
  }

  @Test public void multipleHostnamesForOneCertificate() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("example.com", keypairACertificate1Pin)
        .add("www.example.com", keypairACertificate1Pin)
        .build();

    certificatePinner.check("example.com", keypairACertificate1);
    certificatePinner.check("www.example.com", keypairACertificate1);
  }

  @Test public void absentHostnameMatches() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder().build();
    certificatePinner.check("example.com", keypairACertificate1);
  }

  @Test public void successfulCheckForWildcardHostname() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .build();

    certificatePinner.check("a.example.com", keypairACertificate1);
  }

  @Test public void successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairBCertificate1Pin)
        .build();

    certificatePinner.check("a.example.com", keypairACertificate1, keypairBCertificate1);
  }

  @Test public void unsuccessfulCheckForWildcardHostname() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", keypairBCertificate1);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void multipleCertificatesForOneWildcardHostname() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin, keypairBCertificate1Pin)
        .build();

    certificatePinner.check("a.example.com", keypairACertificate1);
    certificatePinner.check("a.example.com", keypairBCertificate1);
  }

  @Test public void successfulCheckForOneHostnameWithWildcardAndDirectCertificate() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .add("a.example.com", keypairBCertificate1Pin)
        .build();

    certificatePinner.check("a.example.com", keypairACertificate1);
    certificatePinner.check("a.example.com", keypairBCertificate1);
  }

  @Test public void unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .add("a.example.com", keypairBCertificate1Pin)
        .build();

    try {
      certificatePinner.check("a.example.com", keypairCCertificate1);
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void successfulFindMatchingPins() {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("first.com", keypairACertificate1Pin, keypairBCertificate1Pin)
        .add("second.com", keypairCCertificate1Pin)
        .build();

    Set<ByteString> expectedPins = setOf(keypairACertificate1PinBase64, keypairBCertificate1PinBase64);
    Set<ByteString> matchedPins  = certificatePinner.findMatchingPins("first.com");

    assertEquals(expectedPins, matchedPins);
  }

  @Test public void successfulFindMatchingPinsForWildcardAndDirectCertificates() {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .add("a.example.com", keypairBCertificate1Pin)
        .add("b.example.com", keypairCCertificate1Pin)
        .build();

    Set<ByteString> expectedPins = setOf(keypairACertificate1PinBase64, keypairBCertificate1PinBase64);
    Set<ByteString> matchedPins  = certificatePinner.findMatchingPins("a.example.com");

    assertEquals(expectedPins, matchedPins);
  }

  @Test public void wildcardHostnameShouldNotMatchThroughDot() throws Exception {
    Sha1CertificatePinner certificatePinner = new Sha1CertificatePinner.Builder()
        .add("*.example.com", keypairACertificate1Pin)
        .build();

    assertNull(certificatePinner.findMatchingPins("example.com"));
    assertNull(certificatePinner.findMatchingPins("a.b.example.com"));
  }
}
