/*
 * Copyright (C) 2020 Square, Inc.
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

@file:JvmName("Certificates")

package okhttp3.tls

import java.security.GeneralSecurityException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Decodes a multiline string that contains a [certificate][certificatePem] which is
 * [PEM-encoded][rfc_7468]. A typical input string looks like this:
 *
 * ```
 * -----BEGIN CERTIFICATE-----
 * MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
 * cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
 * MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
 * cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
 * ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
 * HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
 * AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
 * yyaoEufLKVXhrTQhRfodTeigi4RX
 * -----END CERTIFICATE-----
 * ```
 */
fun String.decodeCertificatePem(): X509Certificate {
  try {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val certificates = certificateFactory
        .generateCertificates(
            Buffer().writeUtf8(this).inputStream())

    return certificates.single() as X509Certificate
  } catch (nsee: NoSuchElementException) {
    throw IllegalArgumentException("failed to decode certificate", nsee)
  } catch (iae: IllegalArgumentException) {
    throw IllegalArgumentException("failed to decode certificate", iae)
  } catch (e: GeneralSecurityException) {
    throw IllegalArgumentException("failed to decode certificate", e)
  }
}

/**
 * Returns the certificate encoded in [PEM format][rfc_7468].
 *
 * [rfc_7468]: https://tools.ietf.org/html/rfc7468
 */
fun X509Certificate.certificatePem(): String {
  return buildString {
    append("-----BEGIN CERTIFICATE-----\n")
    encodeBase64Lines(encoded.toByteString())
    append("-----END CERTIFICATE-----\n")
  }
}

internal fun StringBuilder.encodeBase64Lines(data: ByteString) {
  val base64 = data.base64()
  for (i in 0 until base64.length step 64) {
    append(base64, i, minOf(i + 64, base64.length)).append('\n')
  }
}
