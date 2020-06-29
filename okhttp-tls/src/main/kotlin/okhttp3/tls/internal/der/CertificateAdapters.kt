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

import okio.ByteString
import okio.IOException
import java.math.BigInteger

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
                       ?: throw IOException("expected time but was exhausted at $reader")

      return when {
        peekHeader.tagClass == Adapters.UTC_TIME.tagClass &&
        peekHeader.tag == Adapters.UTC_TIME.tag -> {
          Adapters.UTC_TIME.fromDer(reader)
        }
        peekHeader.tagClass == Adapters.GENERALIZED_TIME.tagClass &&
        peekHeader.tag == Adapters.GENERALIZED_TIME.tag -> {
          Adapters.GENERALIZED_TIME.fromDer(reader)
        }
        else -> throw IOException("expected time but was $peekHeader at $reader")
      }
    }

    override fun toDer(writer: DerWriter, value: Long) {
      if (value < 2_524_608_000_000L) { // 2050-01-01T00:00:00Z
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
    generalNameIpAddress
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
      ObjectIdentifiers.attestation -> attestation
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
    Adapters.any(),
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

  internal val rootOfTrust: BasicDerAdapter<Attestation.AuthorizationList.RootOfTrust> = Adapters.sequence(
    "PrivateKeyInfo",
    Adapters.OCTET_STRING,
    Adapters.BOOLEAN,
    Adapters.enumerated<Attestation.AuthorizationList.RootOfTrust.VerifiedBootState>(),
    Adapters.OCTET_STRING,
    decompose = {
      listOf(
        it.verifiedBootKey,
        it.deviceLocked,
        it.verifiedBootState,
        it.verifiedBootHash
      )
    },
    construct = {
      Attestation.AuthorizationList.RootOfTrust(
        verifiedBootKey = it[0] as ByteString,
        deviceLocked = it[1] as Boolean,
        verifiedBootState = it[2] as Attestation.AuthorizationList.RootOfTrust.VerifiedBootState,
        verifiedBootHash = it[3] as ByteString
      )
    }
  )

  /**
   * ```
   * AuthorizationList ::= SEQUENCE {
   * purpose                     [1] EXPLICIT SET OF INTEGER OPTIONAL,
   * algorithm                   [2] EXPLICIT INTEGER OPTIONAL,
   * keySize                     [3] EXPLICIT INTEGER OPTIONAL.
   * digest                      [5] EXPLICIT SET OF INTEGER OPTIONAL,
   * padding                     [6] EXPLICIT SET OF INTEGER OPTIONAL,
   * ecCurve                     [10] EXPLICIT INTEGER OPTIONAL,
   * rsaPublicExponent           [200] EXPLICIT INTEGER OPTIONAL,
   * rollbackResistance          [303] EXPLICIT NULL OPTIONAL, # KM4
   * activeDateTime              [400] EXPLICIT INTEGER OPTIONAL
   * originationExpireDateTime   [401] EXPLICIT INTEGER OPTIONAL
   * usageExpireDateTime         [402] EXPLICIT INTEGER OPTIONAL
   * noAuthRequired              [503] EXPLICIT NULL OPTIONAL,
   * userAuthType                [504] EXPLICIT INTEGER OPTIONAL,
   * authTimeout                 [505] EXPLICIT INTEGER OPTIONAL,
   * allowWhileOnBody            [506] EXPLICIT NULL OPTIONAL,
   * trustedUserPresenceRequired [507] EXPLICIT NULL OPTIONAL, # KM4
   * trustedConfirmationRequired [508] EXPLICIT NULL OPTIONAL, # KM4
   * unlockedDeviceRequired      [509] EXPLICIT NULL OPTIONAL, # KM4
   * allApplications             [600] EXPLICIT NULL OPTIONAL,
   * applicationId               [601] EXPLICIT OCTET_STRING OPTIONAL,
   * creationDateTime            [701] EXPLICIT INTEGER OPTIONAL,
   * origin                      [702] EXPLICIT INTEGER OPTIONAL,
   * rollbackResistant           [703] EXPLICIT NULL OPTIONAL, # KM2 and KM3 only.
   * rootOfTrust                 [704] EXPLICIT RootOfTrust OPTIONAL,
   * osVersion                   [705] EXPLICIT INTEGER OPTIONAL,
   * osPatchLevel                [706] EXPLICIT INTEGER OPTIONAL,
   * attestationApplicationId    [709] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdBrand          [710] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdDevice         [711] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdProduct        [712] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdSerial         [713] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdImei           [714] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdMeid           [715] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdManufacturer   [716] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * attestationIdModel          [717] EXPLICIT OCTET_STRING OPTIONAL, # KM3
   * vendorPatchLevel            [718] EXPLICIT INTEGER OPTIONAL, # KM4
   * bootPatchLevel              [719] EXPLICIT INTEGER OPTIONAL, # KM4
   * }
   *
   * RootOfTrust ::= SEQUENCE {
   * verifiedBootKey            OCTET_STRING,
   * deviceLocked               BOOLEAN,
   * verifiedBootState          VerifiedBootState,
   * verifiedBootHash           OCTET_STRING, # KM4
   * }
   *
   * VerifiedBootState ::= ENUMERATED {
   * Verified                   (0),
   * SelfSigned                 (1),
   * Unverified                 (2),
   * Failed                     (3),
   * }
   * ```
   */
  internal val attestationAuthorizationList: BasicDerAdapter<Attestation.AuthorizationList> =
    Adapters.sequence(
      "Attestation",
      1L to Adapters.sequence(
        "SET OF",
        17L to Adapters.INTEGER_AS_LONG.withTag(tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 17),
        decompose = { TODO() },
        construct = { (it as List<Long>).toSet() }
      ).withTag(tag = 1L),
      2L to Adapters.INTEGER_AS_LONG.withTag(128, 2),
      3L to Adapters.INTEGER_AS_LONG.withTag(128, 3),
      5L to Adapters.sequence(
        "SET OF",
        17L to Adapters.INTEGER_AS_LONG.withTag(tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 17),
        decompose = { TODO() },
        construct = { (it as List<Long>).toSet() }
      ).withTag(tag = 5L),
      6L to Adapters.sequence(
        "SET OF",
        17L to Adapters.INTEGER_AS_LONG.withTag(tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 17),
        decompose = { TODO() },
        construct = { (it as List<Long>).toSet() }
      ).withTag(tag = 6L),
      10L to Adapters.INTEGER_AS_LONG.withTag(128, 10),
      200L to Adapters.INTEGER_AS_LONG.withTag(128, 200),
      303L to Adapters.NULL,
      400L to Adapters.INTEGER_AS_LONG.withTag(128, 400),
      401L to Adapters.INTEGER_AS_LONG.withTag(128, 401),
      402L to Adapters.INTEGER_AS_LONG.withTag(128, 402),
      503L to Adapters.NULL.withTag(128, 503),
      504L to Adapters.INTEGER_AS_LONG.withTag(128, 504),
      505L to Adapters.INTEGER_AS_LONG.withTag(128, 505),
      506L to Adapters.NULL,
      507L to Adapters.NULL,
      508L to Adapters.NULL,
      509L to Adapters.NULL,
      600L to Adapters.NULL,
      601L to Adapters.OCTET_STRING.withTag(128, 601),
      701L to Adapters.INTEGER_AS_LONG.withTag(128, 701),
      702L to Adapters.INTEGER_AS_LONG.withTag(128, 702),
      703L to Adapters.NULL,
      704L to rootOfTrust,
      705L to Adapters.INTEGER_AS_LONG.withTag(128, 705),
      706L to Adapters.INTEGER_AS_LONG.withTag(128, 706),
      709L to Adapters.OCTET_STRING.withTag(128, 709),
      710L to Adapters.OCTET_STRING,
      711L to Adapters.OCTET_STRING,
      712L to Adapters.OCTET_STRING,
      713L to Adapters.OCTET_STRING,
      714L to Adapters.OCTET_STRING,
      715L to Adapters.OCTET_STRING,
      716L to Adapters.OCTET_STRING,
      717L to Adapters.OCTET_STRING,
      718L to Adapters.INTEGER_AS_LONG.withTag(128, 718),
      719L to Adapters.INTEGER_AS_LONG.withTag(128, 719),
      decompose = {
        listOf(it.purpose
               , it.algorithm
               , it.keySize
               , it.digest
               , it.padding
               , it.ecCurve
               , it.rsaPublicExponent
               , it.rollbackResistance
               , it.activeDateTime
               , it.originationExpireDateTime
               , it.usageExpireDateTime
               , it.noAuthRequired
               , it.userAuthType
               , it.authTimeout
               , it.allowWhileOnBody
               , it.trustedUserPresenceRequired
               , it.trustedConfirmationRequired
               , it.unlockedDeviceRequired
               , it.allApplications
               , it.applicationId
               , it.creationDateTime
               , it.origin
               , it.rollbackResistant
               , it.rootOfTrust
               , it.osVersion
               , it.osPatchLevel
               , it.attestationApplicationId
               , it.attestationIdBrand
               , it.attestationIdDevice
               , it.attestationIdProduct
               , it.attestationIdSerial
               , it.attestationIdImei
               , it.attestationIdMeid
               , it.attestationIdManufacturer
               , it.attestationIdModel
               , it.vendorPatchLevel
               , it.bootPatchLevel
        )
      },
      construct = {
        Attestation.AuthorizationList(
          purpose = it[0] as Set<Int>?,
          algorithm = it[1] as Long?,
          keySize = it[2] as Long?,
          digest = it[3] as Set<Int>?,
          padding = it[4] as Set<Int>?,
          ecCurve = it[5] as Long?,
          rsaPublicExponent = it[6] as Long?,
          rollbackResistance = it[7],
          activeDateTime = it[8] as Long?,
          originationExpireDateTime = it[9] as Long?,
          usageExpireDateTime = it[10] as Long?,
          noAuthRequired = it[11],
          userAuthType = it[12] as Long?,
          authTimeout = it[13] as Long?,
          allowWhileOnBody = it[14],
          trustedUserPresenceRequired = it[15],
          trustedConfirmationRequired = it[16],
          unlockedDeviceRequired = it[17],
          allApplications = it[18],
          applicationId = it[19] as ByteString?,
          creationDateTime = it[20] as Long?,
          origin = it[21] as Long?,
          rollbackResistant = it[22],
          rootOfTrust = it[23] as Attestation.AuthorizationList.RootOfTrust?,
          osVersion = it[24] as Long?,
          osPatchLevel = it[25] as Long?,
          attestationApplicationId = it[26] as ByteString?,
          attestationIdBrand = it[27] as ByteString?,
          attestationIdDevice = it[28] as ByteString?,
          attestationIdProduct = it[29] as ByteString?,
          attestationIdSerial = it[30] as ByteString?,
          attestationIdImei = it[31] as ByteString?,
          attestationIdMeid = it[32] as ByteString?,
          attestationIdManufacturer = it[33] as ByteString?,
          attestationIdModel = it[34] as ByteString?,
          vendorPatchLevel = it[35] as Long?,
          bootPatchLevel = it[36] as Long?
        )
      }
    )

  /**
   * ```
   * KeyDescription ::= SEQUENCE {
   * attestationVersion         INTEGER, # KM2 value is 1. KM3 value is 2. KM4 value is 3.
   * attestationSecurityLevel   SecurityLevel,
   * keymasterVersion           INTEGER,
   * keymasterSecurityLevel     SecurityLevel,
   * attestationChallenge       OCTET_STRING,
   * uniqueId                   OCTET_STRING,
   * softwareEnforced           AuthorizationList,
   * teeEnforced                AuthorizationList,
   * }
   *
   * SecurityLevel ::= ENUMERATED {
   * Software                   (0),
   * TrustedEnvironment         (1),
   * StrongBox                  (2),
   * }
   * ```
   */
  internal val attestation: BasicDerAdapter<Attestation> = Adapters.sequence(
    "Attestation",
    Adapters.INTEGER_AS_LONG,
    Adapters.enumerated<Attestation.SecurityLevel>(),
    Adapters.INTEGER_AS_LONG,
    Adapters.enumerated<Attestation.SecurityLevel>(),
    Adapters.OCTET_STRING,
    Adapters.OCTET_STRING,
    attestationAuthorizationList,
    attestationAuthorizationList,
    decompose = {
      listOf(
        it.attestationVersion,
        it.attestationSecurityLevel,
        it.keymasterVersion,
        it.keymasterSecurityLevel,
        it.attestationChallenge,
        it.uniqueId,
        it.softwareEnforced,
        it.teeEnforced
      )
    },
    construct = {
      Attestation(
        attestationVersion = it[0] as Long,
        attestationSecurityLevel = it[1] as Attestation.SecurityLevel,
        keymasterVersion = it[2] as Long,
        keymasterSecurityLevel = it[3] as Attestation.SecurityLevel,
        attestationChallenge = it[4] as ByteString,
        uniqueId = it[5] as ByteString,
        softwareEnforced = it[6] as Attestation.AuthorizationList,
        teeEnforced = it[6] as Attestation.AuthorizationList
      )
    }
  )
}