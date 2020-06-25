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
import okio.ByteString

internal data class Certificate(
  val tbsCertificate: TbsCertificate,
  val signatureAlgorithm: AlgorithmIdentifier,
  val signatureValue: BitString
)

internal data class TbsCertificate(
  /** Version ::= INTEGER { v1(0), v2(1), v3(2) } */
  val version: Long,

  /** CertificateSerialNumber ::= INTEGER */
  val serialNumber: BigInteger,
  val signature: AlgorithmIdentifier,
  val issuer: List<List<AttributeTypeAndValue>>,
  val validity: Validity,
  val subject: List<List<AttributeTypeAndValue>>,
  val subjectPublicKeyInfo: SubjectPublicKeyInfo,

  /** UniqueIdentifier ::= BIT STRING */
  val issuerUniqueID: BitString?,

  /** UniqueIdentifier ::= BIT STRING */
  val subjectUniqueID: BitString?,

  /** Extensions ::= SEQUENCE SIZE (1..MAX) OF Extension */
  val extensions: List<Extension>
) {
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
  val algorithm: String,
  val parameters: Any?
)

internal data class AttributeTypeAndValue(
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
  val extnID: String,
  val critical: Boolean,
  val extnValue: ByteString
)
