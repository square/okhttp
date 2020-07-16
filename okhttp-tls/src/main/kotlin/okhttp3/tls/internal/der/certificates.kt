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
package okhttp3.tls.internal.der

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okio.Buffer
import okio.ByteString

internal data class Certificate(
  val tbsCertificate: TbsCertificate,
  val signatureAlgorithm: AlgorithmIdentifier,
  val signatureValue: BitString
) {
  val commonName: Any?
    get() {
      return tbsCertificate.subject
          .flatten()
          .firstOrNull { it.type == ObjectIdentifiers.commonName }
          ?.value
    }

  val organizationalUnitName: Any?
    get() {
      return tbsCertificate.subject
          .flatten()
          .firstOrNull { it.type == ObjectIdentifiers.organizationalUnitName }
          ?.value
    }

  val subjectAlternativeNames: Extension?
    get() {
      return tbsCertificate.extensions.firstOrNull {
        it.id == ObjectIdentifiers.subjectAlternativeName
      }
    }

  val basicConstraints: Extension
    get() {
      return tbsCertificate.extensions.first {
        it.id == ObjectIdentifiers.basicConstraints
      }
    }

  /** Returns true if the certificate was signed by [issuer]. */
  @Throws(SignatureException::class)
  fun checkSignature(issuer: PublicKey): Boolean {
    val signedData = CertificateAdapters.tbsCertificate.toDer(tbsCertificate)

    return Signature.getInstance(tbsCertificate.signatureAlgorithmName).run {
      initVerify(issuer)
      update(signedData.toByteArray())
      verify(signatureValue.byteString.toByteArray())
    }
  }

  fun toX509Certificate(): X509Certificate {
    val data = CertificateAdapters.certificate.toDer(this)
    try {
      val certificateFactory = CertificateFactory.getInstance("X.509")
      val certificates = certificateFactory.generateCertificates(Buffer().write(data).inputStream())
      return certificates.single() as X509Certificate
    } catch (e: NoSuchElementException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    } catch (e: GeneralSecurityException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    }
  }
}

internal data class TbsCertificate(
  /** This is a integer enum. Use 0L for v1, 1L for v2, and 2L for v3. */
  val version: Long,
  val serialNumber: BigInteger,
  val signature: AlgorithmIdentifier,
  val issuer: List<List<AttributeTypeAndValue>>,
  val validity: Validity,
  val subject: List<List<AttributeTypeAndValue>>,
  val subjectPublicKeyInfo: SubjectPublicKeyInfo,
  val issuerUniqueID: BitString?,
  val subjectUniqueID: BitString?,
  val extensions: List<Extension>
) {
  /**
   * Returns the standard name of this certificate's signature algorithm as specified by
   * [Signature.getInstance]. Typical values are like "SHA256WithRSA".
   */
  val signatureAlgorithmName: String
    get() {
      return when (signature.algorithm) {
        ObjectIdentifiers.sha256WithRSAEncryption -> "SHA256WithRSA"
        ObjectIdentifiers.sha256withEcdsa -> "SHA256withECDSA"
        else -> error("unexpected signature algorithm: ${signature.algorithm}")
      }
    }

  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + version.toInt()
    result = 31 * result + serialNumber.hashCode()
    result = 31 * result + signature.hashCode()
    result = 31 * result + issuer.hashCode()
    result = 31 * result + validity.hashCode()
    result = 31 * result + subject.hashCode()
    result = 31 * result + subjectPublicKeyInfo.hashCode()
    result = 31 * result + (issuerUniqueID?.hashCode() ?: 0)
    result = 31 * result + (subjectUniqueID?.hashCode() ?: 0)
    result = 31 * result + extensions.hashCode()
    return result
  }
}

internal data class AlgorithmIdentifier(
  /** An OID string like "1.2.840.113549.1.1.11" for sha256WithRSAEncryption. */
  val algorithm: String,
  /** Parameters of a type implied by [algorithm]. */
  val parameters: Any?
)

internal data class AttributeTypeAndValue(
  /** An OID string like "2.5.4.11" for organizationalUnitName. */
  val type: String,
  val value: Any?
)

internal data class Validity(
  val notBefore: Long,
  val notAfter: Long
) {
  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + notBefore.toInt()
    result = 31 * result + notAfter.toInt()
    return result
  }
}

internal data class SubjectPublicKeyInfo(
  val algorithm: AlgorithmIdentifier,
  val subjectPublicKey: BitString
)

internal data class Extension(
  val id: String,
  val critical: Boolean,
  val value: Any?
)

internal data class BasicConstraints(
  /** True if this certificate can be used as a Certificate Authority (CA). */
  val ca: Boolean,
  /** The maximum number of intermediate CAs between this and leaf certificates. */
  val maxIntermediateCas: Long?
)

/** A private key. Note that this class doesn't support attributes or an embedded public key. */
internal data class PrivateKeyInfo(
  val version: Long, // v1(0), v2(1)
  val algorithmIdentifier: AlgorithmIdentifier, // v1(0), v2(1)
  val privateKey: ByteString
) {
  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + version.toInt()
    result = 31 * result + algorithmIdentifier.hashCode()
    result = 31 * result + privateKey.hashCode()
    return result
  }
}
