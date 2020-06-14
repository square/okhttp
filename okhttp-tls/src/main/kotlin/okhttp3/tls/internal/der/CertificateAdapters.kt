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

/**
 * ASN.1 adapters adapted from the specifications in [RFC 5280][rfc_5280].
 *
 * [rfc_5280]: https://tools.ietf.org/html/rfc5280
 */
internal object CertificateAdapters {
  /**
   * ```
   * Time ::= CHOICE {
   *   utcTime        UTCTime,
   *   generalTime    GeneralizedTime
   * }
   * ```
   */
  internal val time = Adapters.choice(
      Adapters.UTC_TIME,
      Adapters.GENERALIZED_TIME
  )

  /**
   * ```
   * Validity ::= SEQUENCE {
   *   notBefore      Time,
   *   notAfter       Time
   * }
   * ```
   */
  internal val validity = Adapters.sequence(time, time) {
    Validity((it[0] as Pair<*, *>).second as Long, (it[1] as Pair<*, *>).second as Long)
  }

  /**
   * ```
   * AlgorithmIdentifier ::= SEQUENCE  {
   *   algorithm      OBJECT IDENTIFIER,
   *   parameters     ANY DEFINED BY algorithm OPTIONAL
   * }
   * ```
   */
  internal val algorithmIdentifier = Adapters.sequence(
      Adapters.OBJECT_IDENTIFIER,
      Adapters.any().optional()
  ) {
    AlgorithmIdentifier(
        it[0] as String, it[1]
    )
  }

  /**
   * ```
   * Extension ::= SEQUENCE  {
   *   extnID      OBJECT IDENTIFIER,
   *   critical    BOOLEAN DEFAULT FALSE,
   *   extnValue   OCTET STRING
   *     -- contains the DER encoding of an ASN.1 value
   *     -- corresponding to the extension type identified
   *     -- by extnID
   * }
   * ```
   */
  internal val extension = Adapters.sequence(
      Adapters.OBJECT_IDENTIFIER,
      Adapters.BOOLEAN.optional(defaultValue = false),
      Adapters.OCTET_STRING
  ) {
    Extension(
        it[0] as String, it[1] as Boolean, it[2] as ByteString
    )
  }

  /**
   * ```
   * AttributeTypeAndValue ::= SEQUENCE {
   *   type     AttributeType,
   *   value    AttributeValue
   * }
   *
   * AttributeType ::= OBJECT IDENTIFIER
   *
   * AttributeValue ::= ANY -- DEFINED BY AttributeType
   * ```
   */
  internal val attributeTypeAndValue = Adapters.sequence(
      Adapters.OBJECT_IDENTIFIER,
      Adapters.any()
  ) {
    AttributeTypeAndValue(
        it[0] as String,
        it[1]
    )
  }

  /**
   * ```
   * RDNSequence ::= SEQUENCE OF RelativeDistinguishedName
   *
   * RelativeDistinguishedName ::= SET SIZE (1..MAX) OF AttributeTypeAndValue
   * ```
   */
  internal val rdnSequence = attributeTypeAndValue.asSetOf().asSequenceOf()

  /**
   * ```
   * Name ::= CHOICE {
   *   -- only one possibility for now --
   *   rdnSequence  RDNSequence
   * }
   * ```
   */
  internal val name = Adapters.choice(
      rdnSequence
  )

  /**
   * ```
   * SubjectPublicKeyInfo ::= SEQUENCE  {
   *   algorithm            AlgorithmIdentifier,
   *   subjectPublicKey     BIT STRING
   * }
   * ```
   */
  internal val subjectPublicKeyInfo = Adapters.sequence(
      algorithmIdentifier,
      Adapters.BIT_STRING
  ) {
    SubjectPublicKeyInfo(
        it[0] as AlgorithmIdentifier,
        it[1] as BitString
    )
  }

  /**
   * ```
   * TBSCertificate ::= SEQUENCE  {
   *   version         [0]  EXPLICIT Version DEFAULT v1,
   *   serialNumber         CertificateSerialNumber,
   *   signature            AlgorithmIdentifier,
   *   issuer               Name,
   *   validity             Validity,
   *   subject              Name,
   *   subjectPublicKeyInfo SubjectPublicKeyInfo,
   *   issuerUniqueID  [1]  IMPLICIT UniqueIdentifier OPTIONAL, -- If present, version MUST be v2 or v3
   *   subjectUniqueID [2]  IMPLICIT UniqueIdentifier OPTIONAL, -- If present, version MUST be v2 or v3
   *   extensions      [3]  EXPLICIT Extensions OPTIONAL -- If present, version MUST be v3
   * }
   * ```
   */
  internal val tbsCertificate = Adapters.sequence(
      Adapters.INTEGER_AS_LONG.withTag(tag = 0L).optional(defaultValue = 0), // v1 == 0
      Adapters.INTEGER_AS_BIG_INTEGER,
      algorithmIdentifier,
      name,
      validity,
      name,
      subjectPublicKeyInfo,
      Adapters.BIT_STRING.withTag(tag = 1L).optional(),
      Adapters.BIT_STRING.withTag(tag = 2L).optional(),
      extension.asSequenceOf().withExplicitBox(tag = 3).optional(defaultValue = listOf())
  ) {
    TbsCertificate(
        it[0] as Long,
        it[1] as BigInteger,
        it[2] as AlgorithmIdentifier,
        (it[3] as Pair<*, *>).second as List<List<AttributeTypeAndValue>>,
        it[4] as Validity,
        (it[5] as Pair<*, *>).second as List<List<AttributeTypeAndValue>>,
        it[6] as SubjectPublicKeyInfo,
        it[7] as BitString?,
        it[8] as BitString?,
        it[9] as List<Extension>
    )
  }

  /**
   * ```
   * Certificate ::= SEQUENCE  {
   *   tbsCertificate       TBSCertificate,
   *   signatureAlgorithm   AlgorithmIdentifier,
   *   signatureValue       BIT STRING
   * }
   * ```
   */
  internal val certificate = Adapters.sequence(
      tbsCertificate,
      algorithmIdentifier,
      Adapters.BIT_STRING
  ) {
    Certificate(
        it[0] as TbsCertificate,
        it[1] as AlgorithmIdentifier,
        it[2] as BitString
    )
  }
}
