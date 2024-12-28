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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertFailsWith
import okhttp3.internal.tls.CertificateChainCleaner.Companion.get
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test

class CertificateChainCleanerTest {
  @Test
  fun equalsFromCertificate() {
    val rootA =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .build()
    val rootB =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .build()
    assertThat(get(rootB.certificate, rootA.certificate))
      .isEqualTo(get(rootA.certificate, rootB.certificate))
  }

  @Test
  fun equalsFromTrustManager() {
    val handshakeCertificates = HandshakeCertificates.Builder().build()
    val x509TrustManager = handshakeCertificates.trustManager
    assertThat(get(x509TrustManager)).isEqualTo(get(x509TrustManager))
  }

  @Test
  fun normalizeSingleSelfSignedCertificate() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .build()
    val cleaner = get(root.certificate)
    assertThat(cleaner.clean(list(root), "hostname")).isEqualTo(list(root))
  }

  @Test
  fun normalizeUnknownSelfSignedCertificate() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .build()
    val cleaner = get()
    assertFailsWith<SSLPeerUnverifiedException> {
      cleaner.clean(list(root), "hostname")
    }
  }

  @Test
  fun orderedChainOfCertificatesWithRoot() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .certificateAuthority(0)
        .signedBy(root)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build()
    val cleaner = get(root.certificate)
    assertThat(cleaner.clean(list(certB, certA, root), "hostname"))
      .isEqualTo(list(certB, certA, root))
  }

  @Test
  fun orderedChainOfCertificatesWithoutRoot() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .certificateAuthority(0)
        .signedBy(root)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build()
    val cleaner = get(root.certificate)
    // Root is added!
    assertThat(cleaner.clean(list(certB, certA), "hostname")).isEqualTo(
      list(certB, certA, root),
    )
  }

  @Test
  fun unorderedChainOfCertificatesWithRoot() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(2)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .certificateAuthority(1)
        .signedBy(root)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .certificateAuthority(0)
        .signedBy(certA)
        .build()
    val certC =
      HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certB)
        .build()
    val cleaner = get(root.certificate)
    assertThat(cleaner.clean(list(certC, certA, root, certB), "hostname")).isEqualTo(
      list(certC, certB, certA, root),
    )
  }

  @Test
  fun unorderedChainOfCertificatesWithoutRoot() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(2)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .certificateAuthority(1)
        .signedBy(root)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .certificateAuthority(0)
        .signedBy(certA)
        .build()
    val certC =
      HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certB)
        .build()
    val cleaner = get(root.certificate)
    assertThat(cleaner.clean(list(certC, certA, certB), "hostname")).isEqualTo(
      list(certC, certB, certA, root),
    )
  }

  @Test
  fun unrelatedCertificatesAreOmitted() {
    val root =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .certificateAuthority(0)
        .signedBy(root)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .signedBy(certA)
        .build()
    val certUnnecessary =
      HeldCertificate.Builder()
        .serialNumber(4L)
        .build()
    val cleaner = get(root.certificate)
    assertThat(cleaner.clean(list(certB, certUnnecessary, certA, root), "hostname"))
      .isEqualTo(
        list(certB, certA, root),
      )
  }

  @Test
  fun chainGoesAllTheWayToSelfSignedRoot() {
    val selfSigned =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(2)
        .build()
    val trusted =
      HeldCertificate.Builder()
        .serialNumber(2L)
        .signedBy(selfSigned)
        .certificateAuthority(1)
        .build()
    val certA =
      HeldCertificate.Builder()
        .serialNumber(3L)
        .certificateAuthority(0)
        .signedBy(trusted)
        .build()
    val certB =
      HeldCertificate.Builder()
        .serialNumber(4L)
        .signedBy(certA)
        .build()
    val cleaner =
      get(
        selfSigned.certificate,
        trusted.certificate,
      )
    assertThat(cleaner.clean(list(certB, certA), "hostname")).isEqualTo(
      list(certB, certA, trusted, selfSigned),
    )
    assertThat(cleaner.clean(list(certB, certA, trusted), "hostname")).isEqualTo(
      list(certB, certA, trusted, selfSigned),
    )
    assertThat(cleaner.clean(list(certB, certA, trusted, selfSigned), "hostname"))
      .isEqualTo(
        list(certB, certA, trusted, selfSigned),
      )
  }

  @Test
  fun trustedRootNotSelfSigned() {
    val unknownSigner =
      HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(2)
        .build()
    val trusted =
      HeldCertificate.Builder()
        .signedBy(unknownSigner)
        .certificateAuthority(1)
        .serialNumber(2L)
        .build()
    val intermediateCa =
      HeldCertificate.Builder()
        .signedBy(trusted)
        .certificateAuthority(0)
        .serialNumber(3L)
        .build()
    val certificate =
      HeldCertificate.Builder()
        .signedBy(intermediateCa)
        .serialNumber(4L)
        .build()
    val cleaner = get(trusted.certificate)
    assertThat(cleaner.clean(list(certificate, intermediateCa), "hostname"))
      .isEqualTo(
        list(certificate, intermediateCa, trusted),
      )
    assertThat(cleaner.clean(list(certificate, intermediateCa, trusted), "hostname"))
      .isEqualTo(
        list(certificate, intermediateCa, trusted),
      )
  }

  @Test
  fun chainMaxLength() {
    val heldCertificates = chainOfLength(10)
    val certificates: MutableList<Certificate> = ArrayList()
    for (heldCertificate in heldCertificates) {
      certificates.add(heldCertificate.certificate)
    }
    val root = heldCertificates[heldCertificates.size - 1].certificate
    val cleaner = get(root)
    assertThat(cleaner.clean(certificates, "hostname")).isEqualTo(certificates)
    assertThat(cleaner.clean(certificates.subList(0, 9), "hostname")).isEqualTo(
      certificates,
    )
  }

  @Test
  fun chainTooLong() {
    val heldCertificates = chainOfLength(11)
    val certificates: MutableList<Certificate> = ArrayList()
    for (heldCertificate in heldCertificates) {
      certificates.add(heldCertificate.certificate)
    }
    val root = heldCertificates[heldCertificates.size - 1].certificate
    val cleaner = get(root)
    assertFailsWith<SSLPeerUnverifiedException> {
      cleaner.clean(certificates, "hostname")
    }
  }

  /** Returns a chain starting at the leaf certificate and progressing to the root.  */
  private fun chainOfLength(length: Int): List<HeldCertificate> {
    val result = mutableListOf<HeldCertificate>()
    for (i in 1..length) {
      result.add(
        0,
        HeldCertificate.Builder()
          .signedBy(if (result.isNotEmpty()) result[0] else null)
          .certificateAuthority(length - i)
          .serialNumber(i.toLong())
          .build(),
      )
    }
    return result
  }

  private fun list(vararg heldCertificates: HeldCertificate): List<Certificate> {
    val result: MutableList<Certificate> = ArrayList()
    for (heldCertificate in heldCertificates) {
      result.add(heldCertificate.certificate)
    }
    return result
  }
}
