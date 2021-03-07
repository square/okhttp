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
import java.net.ProtocolException
import okio.ByteString

/**
 * ASN.1 adapters adapted from the specifications in [RFC 5280][rfc_5280].
 *
 * [rfc_5280]: https://tools.ietf.org/html/rfc5280
 */
@Suppress("UNCHECKED_CAST") // This needs to cast decoded collections.
internal object CertificateAdapters {
  /**
   * ```
   * Time ::= CHOICE {
   *   utcTime        UTCTime,
   *   generalTime    GeneralizedTime
   * }
   * ```
   *
   * RFC 5280, section 4.1.2.5:
   *
   * > CAs conforming to this profile MUST always encode certificate validity dates through the year
   * > 2049 as UTCTime; certificate validity dates in 2050 or later MUST be encoded as
   * > GeneralizedTime.
   */
  internal val time: DerAdapter<Long> = object : DerAdapter<Long> {
    override fun matches(header: DerHeader): Boolean {
      return Adapters.UTC_TIME.matches(header) || Adapters.GENERALIZED_TIME.matches(header)
    }

    override fun fromDer(reader: DerReader): Long {
      val peekHeader = reader.peekHeader()
          ?: throw ProtocolException("expected time but was exhausted at $reader")

      return when {
        peekHeader.tagClass == Adapters.UTC_TIME.tagClass &&
            peekHeader.tag == Adapters.UTC_TIME.tag -> {
          Adapters.UTC_TIME.fromDer(reader)
        }
        peekHeader.tagClass == Adapters.GENERALIZED_TIME.tagClass &&
            peekHeader.tag == Adapters.GENERALIZED_TIME.tag -> {
          Adapters.GENERALIZED_TIME.fromDer(reader)
        }
        else -> throw ProtocolException("expected time but was $peekHeader at $reader")
      }
    }

    override fun toDer(writer: DerWriter, value: Long) {
      // [1950-01-01T00:00:00..2050-01-01T00:00:00Z)
      if (value in -631_152_000_000L until 2_524_608_000_000L) {
        Adapters.UTC_TIME.toDer(writer, value)
      } else {
        Adapters.GENERALIZED_TIME.toDer(writer, value)
      }
    }
  }

  /**
   * ```
   * Validity ::= SEQUENCE {
   *   notBefore      Time,
   *   notAfter       Time
   * }
   * ```
   */
  private val validity: BasicDerAdapter<Validity> = Adapters.sequence(
      "Validity",
      time,
      time,
      decompose = {
        listOf(
            it.notBefore,
            it.notAfter
        )
      },
      construct = {
        Validity(
            notBefore = it[0] as Long,
            notAfter = it[1] as Long
        )
      }
  )

  /** The type of the parameters depends on the algorithm that precedes it. */
  private val algorithmParameters: DerAdapter<Any?> = Adapters.usingTypeHint { typeHint ->
    when (typeHint) {
      // This type is pretty strange. The spec says that for certain algorithms we must encode null
      // when it is present, and for others we must omit it!
      // https://tools.ietf.org/html/rfc4055#section-2.1
      ObjectIdentifiers.sha256WithRSAEncryption -> Adapters.NULL
      ObjectIdentifiers.rsaEncryption -> Adapters.NULL
      ObjectIdentifiers.ecPublicKey -> Adapters.OBJECT_IDENTIFIER
      else -> null
    }
  }

  /**
   * ```
   * AlgorithmIdentifier ::= SEQUENCE  {
   *   algorithm      OBJECT IDENTIFIER,
   *   parameters     ANY DEFINED BY algorithm OPTIONAL
   * }
   * ```
   */
  internal val algorithmIdentifier: BasicDerAdapter<AlgorithmIdentifier> = Adapters.sequence(
      "AlgorithmIdentifier",
      Adapters.OBJECT_IDENTIFIER.asTypeHint(),
      algorithmParameters,
      decompose = {
        listOf(
            it.algorithm,
            it.parameters
        )
      },
      construct = {
        AlgorithmIdentifier(
            algorithm = it[0] as String,
            parameters = it[1]
        )
      }
  )

  /**
   * ```
   * BasicConstraints ::= SEQUENCE {
   *   cA                      BOOLEAN DEFAULT FALSE,
   *   pathLenConstraint       INTEGER (0..MAX) OPTIONAL
   * }
   * ```
   */
  private val basicConstraints: BasicDerAdapter<BasicConstraints> = Adapters.sequence(
      "BasicConstraints",
      Adapters.BOOLEAN.optional(defaultValue = false),
      Adapters.INTEGER_AS_LONG.optional(),
      decompose = {
        listOf(
            it.ca,
            it.maxIntermediateCas
        )
      },
      construct = {
        BasicConstraints(
            ca = it[0] as Boolean,
            maxIntermediateCas = it[1] as Long?
        )
      }
  )

  /**
   * Note that only a subset of available choices are implemented.
   *
   * ```
   * GeneralName ::= CHOICE {
   *   otherName                       [0]     OtherName,
   *   rfc822Name                      [1]     IA5String,
   *   dNSName                         [2]     IA5String,
   *   x400Address                     [3]     ORAddress,
   *   directoryName                   [4]     Name,
   *   ediPartyName                    [5]     EDIPartyName,
   *   uniformResourceIdentifier       [6]     IA5String,
   *   iPAddress                       [7]     OCTET STRING,
   *   registeredID                    [8]     OBJECT IDENTIFIER
   * }
   * ```
   *
   * The first property of the pair is the adapter that was used, the second property is the value.
   */
  internal val generalNameDnsName = Adapters.IA5_STRING.withTag(tag = 2L)
  internal val generalNameIpAddress = Adapters.OCTET_STRING.withTag(tag = 7L)
  internal val generalName: DerAdapter<Pair<DerAdapter<*>, Any?>> = Adapters.choice(
      generalNameDnsName,
      generalNameIpAddress,
      Adapters.ANY_VALUE
  )

  /**
   * ```
   * SubjectAltName ::= GeneralNames
   *
   * GeneralNames ::= SEQUENCE SIZE (1..MAX) OF GeneralName
   * ```
   */
  private val subjectAlternativeName: BasicDerAdapter<List<Pair<DerAdapter<*>, Any?>>> =
    generalName.asSequenceOf()

  /**
   * This uses the preceding extension ID to select which adapter to use for the extension value
   * that follows.
   */
  private val extensionValue: BasicDerAdapter<Any?> = Adapters.usingTypeHint { typeHint ->
    when (typeHint) {
      ObjectIdentifiers.subjectAlternativeName -> subjectAlternativeName
      ObjectIdentifiers.basicConstraints -> basicConstraints
      else -> null
    }
  }.withExplicitBox(
      tagClass = Adapters.OCTET_STRING.tagClass,
      tag = Adapters.OCTET_STRING.tag,
      forceConstructed = false
  )

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
  internal val extension: BasicDerAdapter<Extension> = Adapters.sequence(
      "Extension",
      Adapters.OBJECT_IDENTIFIER.asTypeHint(),
      Adapters.BOOLEAN.optional(defaultValue = false),
      extensionValue,
      decompose = {
        listOf(
            it.id,
            it.critical,
            it.value
        )
      },
      construct = {
        Extension(
            id = it[0] as String,
            critical = it[1] as Boolean,
            value = it[2]
        )
      }
  )

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
  private val attributeTypeAndValue: BasicDerAdapter<AttributeTypeAndValue> = Adapters.sequence(
      "AttributeTypeAndValue",
      Adapters.OBJECT_IDENTIFIER,
      Adapters.any(
          String::class to Adapters.UTF8_STRING,
          Nothing::class to Adapters.PRINTABLE_STRING,
          AnyValue::class to Adapters.ANY_VALUE
      ),
      decompose = {
        listOf(
            it.type,
            it.value
        )
      },
      construct = {
        AttributeTypeAndValue(
            type = it[0] as String,
            value = it[1]
        )
      }
  )

  /**
   * ```
   * RDNSequence ::= SEQUENCE OF RelativeDistinguishedName
   *
   * RelativeDistinguishedName ::= SET SIZE (1..MAX) OF AttributeTypeAndValue
   * ```
   */
  internal val rdnSequence: BasicDerAdapter<List<List<AttributeTypeAndValue>>> =
    attributeTypeAndValue.asSetOf().asSequenceOf()

  /**
   * ```
   * Name ::= CHOICE {
   *   -- only one possibility for now --
   *   rdnSequence  RDNSequence
   * }
   * ```
   */
  internal val name: DerAdapter<Pair<DerAdapter<*>, Any?>> = Adapters.choice(
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
  internal val subjectPublicKeyInfo: BasicDerAdapter<SubjectPublicKeyInfo> = Adapters.sequence(
      "SubjectPublicKeyInfo",
      algorithmIdentifier,
      Adapters.BIT_STRING,
      decompose = {
        listOf(
            it.algorithm,
            it.subjectPublicKey
        )
      },
      construct = {
        SubjectPublicKeyInfo(
            algorithm = it[0] as AlgorithmIdentifier,
            subjectPublicKey = it[1] as BitString
        )
      }
  )

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
  internal val tbsCertificate: BasicDerAdapter<TbsCertificate> = Adapters.sequence(
      "TBSCertificate",
      Adapters.INTEGER_AS_LONG.withExplicitBox(tag = 0L).optional(defaultValue = 0), // v1 == 0
      Adapters.INTEGER_AS_BIG_INTEGER,
      algorithmIdentifier,
      name,
      validity,
      name,
      subjectPublicKeyInfo,
      Adapters.BIT_STRING.withTag(tag = 1L).optional(),
      Adapters.BIT_STRING.withTag(tag = 2L).optional(),
      extension.asSequenceOf().withExplicitBox(tag = 3).optional(defaultValue = listOf()),
      decompose = {
        listOf(
            it.version,
            it.serialNumber,
            it.signature,
            rdnSequence to it.issuer,
            it.validity,
            rdnSequence to it.subject,
            it.subjectPublicKeyInfo,
            it.issuerUniqueID,
            it.subjectUniqueID,
            it.extensions
        )
      },
      construct = {
        TbsCertificate(
            version = it[0] as Long,
            serialNumber = it[1] as BigInteger,
            signature = it[2] as AlgorithmIdentifier,
            issuer = (it[3] as Pair<*, *>).second as List<List<AttributeTypeAndValue>>,
            validity = it[4] as Validity,
            subject = (it[5] as Pair<*, *>).second as List<List<AttributeTypeAndValue>>,
            subjectPublicKeyInfo = it[6] as SubjectPublicKeyInfo,
            issuerUniqueID = it[7] as BitString?,
            subjectUniqueID = it[8] as BitString?,
            extensions = it[9] as List<Extension>
        )
      }
  )

  /**
   * ```
   * Certificate ::= SEQUENCE  {
   *   tbsCertificate       TBSCertificate,
   *   signatureAlgorithm   AlgorithmIdentifier,
   *   signatureValue       BIT STRING
   * }
   * ```
   */
  internal val certificate: BasicDerAdapter<Certificate> = Adapters.sequence(
      "Certificate",
      tbsCertificate,
      algorithmIdentifier,
      Adapters.BIT_STRING,
      decompose = {
        listOf(
            it.tbsCertificate,
            it.signatureAlgorithm,
            it.signatureValue
        )
      },
      construct = {
        Certificate(
            tbsCertificate = it[0] as TbsCertificate,
            signatureAlgorithm = it[1] as AlgorithmIdentifier,
            signatureValue = it[2] as BitString
        )
      }
  )

  /**
   * ```
   * Version ::= INTEGER { v1(0), v2(1) } (v1, ..., v2)
   *
   * PrivateKeyAlgorithmIdentifier ::= AlgorithmIdentifier
   *
   * PrivateKey ::= OCTET STRING
   *
   * OneAsymmetricKey ::= SEQUENCE {
   *   version                   Version,
   *   privateKeyAlgorithm       PrivateKeyAlgorithmIdentifier,
   *   privateKey                PrivateKey,
   *   attributes            [0] Attributes OPTIONAL,
   *   ...,
   *   [[2: publicKey        [1] PublicKey OPTIONAL ]],
   *   ...
   * }
   *
   * PrivateKeyInfo ::= OneAsymmetricKey
   * ```
   */
  internal val privateKeyInfo: BasicDerAdapter<PrivateKeyInfo> = Adapters.sequence(
      "PrivateKeyInfo",
      Adapters.INTEGER_AS_LONG,
      algorithmIdentifier,
      Adapters.OCTET_STRING,
      decompose = {
        listOf(
            it.version,
            it.algorithmIdentifier,
            it.privateKey
        )
      },
      construct = {
        PrivateKeyInfo(
            version = it[0] as Long,
            algorithmIdentifier = it[1] as AlgorithmIdentifier,
            privateKey = it[2] as ByteString
        )
      }
  )
}
