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

import okhttp3.CertificatePinner.Companion.newPin
import okhttp3.CertificatePinner.Companion.toSha1ByteString
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CertificatePinnerKotlinTest {

  @Test
  fun successfulCheckSha1Pin() {
    val certificatePinner = CertificatePinner.Builder()
        .add("example.com", "sha1/" + certA1.certificate.toSha1ByteString().base64())
        .build()

    certificatePinner.check("example.com", listOf(certA1.certificate))
  }

  @Test fun successfulFindMatchingPins() {
    val certificatePinner = CertificatePinner.Builder()
        .add("first.com", certA1Sha256Pin, certB1Sha256Pin)
        .add("second.com", certC1Sha256Pin)
        .build()

    val expectedPins = listOf(
        newPin("first.com", certA1Sha256Pin),
        newPin("first.com", certB1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("first.com")).isEqualTo(expectedPins)
  }

  @Test fun successfulFindMatchingPinsForWildcardAndDirectCertificates() {
    val certificatePinner = CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .add("b.example.com", certC1Sha256Pin)
        .build()

    val expectedPins = listOf(
        newPin("*.example.com", certA1Sha256Pin),
        newPin("a.example.com", certB1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("a.example.com")).isEqualTo(expectedPins)
  }

  @Test
  fun wildcardHostnameShouldNotMatchThroughDot() {
    val certificatePinner = CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()

    assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
  }

  @Test fun successfulFindMatchingPinsIgnoresCase() {
    val certificatePinner = CertificatePinner.Builder()
        .add("EXAMPLE.com", certA1Sha256Pin)
        .add("*.MyExample.Com", certB1Sha256Pin)
        .build()

    val expectedPin1 = listOf(newPin("EXAMPLE.com", certA1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("example.com")).isEqualTo(expectedPin1)

    val expectedPin2 = listOf(newPin("*.MyExample.Com", certB1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("a.myexample.com")).isEqualTo(expectedPin2)
  }

  @Test fun successfulFindMatchingPinPunycode() {
    val certificatePinner = CertificatePinner.Builder()
        .add("σkhttp.com", certA1Sha256Pin)
        .build()

    val expectedPin = listOf(newPin("σkhttp.com", certA1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("xn--khttp-fde.com")).isEqualTo(expectedPin)
  }

  /** https://github.com/square/okhttp/issues/3324  */
  @Test
  fun checkSubstringMatch() {
    val certificatePinner = CertificatePinner.Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()

    assertThat(certificatePinner.findMatchingPins("a.example.com.notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("example.com.notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("ple.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("com")).isEmpty()

    val expectedPin = newPin("*.example.com", certA1Sha256Pin)
    assertThat(certificatePinner.findMatchingPins("a.example.com")).containsExactly(expectedPin)
    assertThat(certificatePinner.findMatchingPins("example.example.com"))
        .containsExactly(expectedPin)
  }

  companion object {
    internal var certA1: HeldCertificate = HeldCertificate.Builder()
        .serialNumber(100L)
        .build()
    internal var certA1Sha256Pin = CertificatePinner.pin(certA1.certificate)

    private var certB1 = HeldCertificate.Builder()
        .serialNumber(200L)
        .build()
    internal var certB1Sha256Pin = CertificatePinner.pin(certB1.certificate)

    private var certC1 = HeldCertificate.Builder()
        .serialNumber(300L)
        .build()
    internal var certC1Sha256Pin = CertificatePinner.pin(certC1.certificate)
  }
}
