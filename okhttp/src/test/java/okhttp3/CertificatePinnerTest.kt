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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import java.util.Arrays
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertFailsWith
import okhttp3.CertificatePinner.Companion.pin
import okhttp3.CertificatePinner.Companion.sha1Hash
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.decodeBase64
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CertificatePinnerTest {
  @Test
  fun malformedPin() {
    val builder = CertificatePinner.Builder()
    assertFailsWith<IllegalArgumentException> {
      builder.add("example.com", "md5/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
    }
  }

  @Test
  fun malformedBase64() {
    val builder = CertificatePinner.Builder()
    assertFailsWith<IllegalArgumentException> {
      builder.add("example.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw*")
    }
  }

  /** Multiple certificates generated from the same keypair have the same pin.  */
  @Test
  fun sameKeypairSamePin() {
    val heldCertificateA2 =
      HeldCertificate.Builder()
        .keyPair(certA1.keyPair)
        .serialNumber(101L)
        .build()
    val keypairACertificate2Pin = pin(heldCertificateA2.certificate)
    val heldCertificateB2 =
      HeldCertificate.Builder()
        .keyPair(certB1.keyPair)
        .serialNumber(201L)
        .build()
    val keypairBCertificate2Pin = pin(heldCertificateB2.certificate)
    assertThat(keypairACertificate2Pin).isEqualTo(certA1Sha256Pin)
    assertThat(keypairBCertificate2Pin).isEqualTo(certB1Sha256Pin)
    assertThat(certB1Sha256Pin).isNotEqualTo(certA1Sha256Pin)
  }

  @Test
  fun successfulCheck() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build()
    certificatePinner.check("example.com", listOf(certA1.certificate))
  }

  @Test
  fun successfulMatchAcceptsAnyMatchingCertificateOld() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build()
    certificatePinner.check("example.com", certA1.certificate, certB1.certificate)
  }

  @Test
  fun successfulMatchAcceptsAnyMatchingCertificate() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certB1Sha256Pin)
        .build()
    certificatePinner.check(
      "example.com",
      Arrays.asList(certA1.certificate, certB1.certificate),
    )
  }

  @Test
  fun unsuccessfulCheck() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .build()
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("example.com", certB1.certificate)
    }
  }

  @Test
  fun multipleCertificatesForOneHostname() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build()
    certificatePinner.check("example.com", listOf(certA1.certificate))
    certificatePinner.check("example.com", listOf(certB1.certificate))
  }

  @Test
  fun multipleHostnamesForOneCertificate() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin)
        .build()
    certificatePinner.check("example.com", listOf(certA1.certificate))
    certificatePinner.check("www.example.com", listOf(certA1.certificate))
  }

  @Test
  fun absentHostnameMatches() {
    val certificatePinner = CertificatePinner.Builder().build()
    certificatePinner.check("example.com", listOf(certA1.certificate))
  }

  @Test
  fun successfulCheckForWildcardHostname() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()
    certificatePinner.check("a.example.com", listOf(certA1.certificate))
  }

  @Test
  fun successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certB1Sha256Pin)
        .build()
    certificatePinner.check(
      "a.example.com",
      Arrays.asList(certA1.certificate, certB1.certificate),
    )
  }

  @Test
  fun unsuccessfulCheckForWildcardHostname() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("a.example.com", listOf(certB1.certificate))
    }
  }

  @Test
  fun multipleCertificatesForOneWildcardHostname() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin, certB1Sha256Pin)
        .build()
    certificatePinner.check("a.example.com", listOf(certA1.certificate))
    certificatePinner.check("a.example.com", listOf(certB1.certificate))
  }

  @Test
  fun successfulCheckForOneHostnameWithWildcardAndDirectCertificate() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build()
    certificatePinner.check("a.example.com", listOf(certA1.certificate))
    certificatePinner.check("a.example.com", listOf(certB1.certificate))
  }

  @Test
  fun unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .build()
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("a.example.com", listOf(certC1.certificate))
    }
  }

  @Test
  fun checkForHostnameWithDoubleAsterisk() {
    val certificatePinner =
      CertificatePinner.Builder()
        .add("**.example.co.uk", certA1Sha256Pin)
        .build()

    // Should be pinned:
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("example.co.uk", listOf(certB1.certificate))
    }
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("foo.example.co.uk", listOf(certB1.certificate))
    }
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("foo.bar.example.co.uk", listOf(certB1.certificate))
    }
    assertFailsWith<SSLPeerUnverifiedException> {
      certificatePinner.check("foo.bar.baz.example.co.uk", listOf(certB1.certificate))
    }

    // Should not be pinned:
    certificatePinner.check("uk", listOf(certB1.certificate))
    certificatePinner.check("co.uk", listOf(certB1.certificate))
    certificatePinner.check("anotherexample.co.uk", listOf(certB1.certificate))
    certificatePinner.check("foo.anotherexample.co.uk", listOf(certB1.certificate))
  }

  @Test
  fun testBadPin() {
    assertFailsWith<IllegalArgumentException> {
      CertificatePinner.Pin(
        "example.co.uk",
        "sha256/a",
      )
    }
  }

  @Test
  fun testBadAlgorithm() {
    assertFailsWith<IllegalArgumentException> {
      CertificatePinner.Pin(
        "example.co.uk",
        "sha512/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      )
    }
  }

  @Test
  fun testBadHost() {
    assertFailsWith<IllegalArgumentException> {
      CertificatePinner.Pin(
        "example.*",
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      )
    }
  }

  @Test
  fun testGoodPin() {
    val pin =
      CertificatePinner.Pin(
        "**.example.co.uk",
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      )
    assertEquals(
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".decodeBase64(),
      pin.hash,
    )
    assertEquals("sha256", pin.hashAlgorithm)
    assertEquals("**.example.co.uk", pin.pattern)
    Assertions.assertTrue(pin.matchesHostname("www.example.co.uk"))
    Assertions.assertTrue(pin.matchesHostname("gopher.example.co.uk"))
    Assertions.assertFalse(pin.matchesHostname("www.example.com"))
  }

  @Test
  fun testMatchesSha256() {
    val pin = CertificatePinner.Pin("example.com", certA1Sha256Pin)
    Assertions.assertTrue(pin.matchesCertificate(certA1.certificate))
    Assertions.assertFalse(pin.matchesCertificate(certB1.certificate))
  }

  @Test
  fun testMatchesSha1() {
    val pin = CertificatePinner.Pin("example.com", certC1Sha1Pin)
    Assertions.assertTrue(pin.matchesCertificate(certC1.certificate))
    Assertions.assertFalse(pin.matchesCertificate(certB1.certificate))
  }

  @Test
  fun pinList() {
    val builder =
      CertificatePinner.Builder()
        .add("example.com", certA1Sha256Pin)
        .add("www.example.com", certA1Sha256Pin)
    val certificatePinner = builder.build()
    val expectedPins =
      Arrays.asList(
        CertificatePinner.Pin("example.com", certA1Sha256Pin),
        CertificatePinner.Pin("www.example.com", certA1Sha256Pin),
      )
    assertEquals(expectedPins, builder.pins)
    assertEquals(HashSet(expectedPins), certificatePinner.pins)
  }

  companion object {
    val certA1 =
      HeldCertificate.Builder()
        .serialNumber(100L)
        .build()
    val certA1Sha256Pin = pin(certA1.certificate)
    val certB1 =
      HeldCertificate.Builder()
        .serialNumber(200L)
        .build()
    val certB1Sha256Pin = pin(certB1.certificate)
    val certC1 =
      HeldCertificate.Builder()
        .serialNumber(300L)
        .build()
    val certC1Sha1Pin = "sha1/" + certC1.certificate.sha1Hash().base64()
  }
}
