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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

internal class DerTest {
  @Test fun `tag and length`() {
    val buffer = Buffer()
        .writeByte(0b00011110)
        .writeByte(0b10000001)
        .writeByte(0b11001001)

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(tag).isEqualTo(30)
      assertThat(constructed).isFalse()
      assertThat(length).isEqualTo(201)
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `primitive bit string`() {
    val buffer = Buffer()
        .write("0307040A3B5F291CD0".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(derReader.readBitString()).isEqualTo(BitString("0A3B5F291CD0".decodeHex(), 4))
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `constructed bit string`() {
    val buffer = Buffer()
        .write("2380".decodeHex())
        .write("0303000A3B".decodeHex())
        .write("0305045F291CD0".decodeHex())
        .write("0000".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(derReader.readBitString()).isEqualTo(BitString("0A3B5F291CD0".decodeHex(), 4))
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `primitive string`() {
    val buffer = Buffer()
        .write("1A054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(26L)
      assertThat(constructed).isFalse()
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `constructed string`() {
    val buffer = Buffer()
        .write("3A0904034A6F6E04026573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(26L)
      assertThat(constructed).isTrue()
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    val buffer = Buffer()
        .write("43054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    val buffer = Buffer()
        .write("A20743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(2L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(length).isEqualTo(7L)

      derReader.read(derAdapter { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(3L)
        assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
        assertThat(length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      })

      assertThat(derReader.hasNext()).isFalse()
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `implicit tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    // Type4 ::= [APPLICATION 7] IMPLICIT Type3
    val buffer = Buffer()
        .write("670743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(7L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
      assertThat(length).isEqualTo(7L)

      derReader.read(derAdapter { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(3L)
        assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
        assertThat(length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      })

      assertThat(derReader.hasNext()).isFalse()
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `implicit implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type5 ::= [2] IMPLICIT Type2
    val buffer = Buffer()
        .write("82054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(2L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(length).isEqualTo(5L)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `object identifier`() {
    val buffer = Buffer()
        .write("0603883703".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(6L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(length).isEqualTo(3L)
      assertThat(derReader.readObjectIdentifier()).isEqualTo("2.999.3")
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `relative object identifier`() {
    val buffer = Buffer()
        .write("0D04c27B0302".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(13L)
      assertThat(tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(length).isEqualTo(4L)
      assertThat(derReader.readRelativeObjectIdentifier()).isEqualTo("8571.3.2")
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `raw sequence`() {
    val buffer = Buffer()
        .write("300A".decodeHex())
        .write("1505".decodeHex())
        .write("Smith".encodeUtf8())
        .write("01".decodeHex())
        .write("01".decodeHex())
        .write("FF".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read(derAdapter { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(16L)

      derReader.read(derAdapter { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(21L)
        assertThat(derReader.readOctetString()).isEqualTo("Smith".encodeUtf8())
      })

      derReader.read(derAdapter { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(1L)
        assertThat(derReader.readBoolean()).isTrue()
      })

      assertThat(derReader.hasNext()).isFalse()
    })

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `decode sequence of`() {
    val buffer = Buffer()
        .write("3009020107020108020109".decodeHex())
    val derReader = DerReader(buffer)
    val list = derReader.read(Adapters.INTEGER_AS_LONG.asSequenceOf())
    assertThat(list).containsExactly(7L, 8L, 9L)
  }

  @Test fun `decode point with only x set`() {
    val buffer = Buffer()
        .write("3003800109".decodeHex())
    val derReader = DerReader(buffer)
    val point = derReader.read(Point.ADAPTER)
    assertThat(point).isEqualTo(Point(9L, null))
  }

  @Test fun `decode point with only y set`() {
    val buffer = Buffer()
        .write("3003810109".decodeHex())
    val derReader = DerReader(buffer)
    val point = derReader.read(Point.ADAPTER)
    assertThat(point).isEqualTo(Point(null, 9L))
  }

  @Test fun `decode point with both fields set`() {
    val buffer = Buffer()
        .write("3006800109810109".decodeHex())
    val derReader = DerReader(buffer)
    val point = derReader.read(Point.ADAPTER)
    assertThat(point).isEqualTo(Point(9L, 9L))
  }

  @Test fun `decode implicit`() {
    // [5] IMPLICIT UTF8String
    val implicitAdapter = Adapters.UTF8_STRING.withTag(tag = 5L)

    val buffer = Buffer().write("85026869".decodeHex())
    val derReader = DerReader(buffer)
    val string = derReader.read(implicitAdapter)
    assertThat(string).isEqualTo("hi")
  }

  @Test fun `decode explicit`() {
    // [5] EXPLICIT UTF8String
    val explicitAdapter = Adapters.UTF8_STRING.withExplicitBox(tag = 5L)

    val buffer = Buffer().write("A5040C026869".decodeHex())
    val derReader = DerReader(buffer)
    val string = derReader.read(explicitAdapter)
    assertThat(string).isEqualTo("hi")
  }

  @Test fun `decode boolean`() {
    val adapter = Adapters.BOOLEAN
    val buffer = Buffer()
        .write("0101FF".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(true)
  }

  @Test fun `decode positive integer`() {
    val adapter = Adapters.INTEGER_AS_LONG
    val buffer = Buffer()
        .writeByte(adapter.tagClass or adapter.tag.toInt())
        .writeByte(1)
        .writeByte(0b00110010)
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(50L)
  }

  @Test fun `decode negative integer`() {
    val adapter = Adapters.INTEGER_AS_LONG
    val buffer = Buffer()
        .writeByte(adapter.tagClass or adapter.tag.toInt())
        .writeByte(1)
        .writeByte(0b10011100)
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(-100L)
  }

  @Test fun `decode five byte integer`() {
    val adapter = Adapters.INTEGER_AS_LONG
    val buffer = Buffer()
        .writeByte(adapter.tagClass or adapter.tag.toInt())
        .writeByte(5)
        .writeByte(0b10000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000001)
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(-549755813887L)
  }

  @Test fun `decode with eight zeros`() {
    val adapter = Adapters.INTEGER_AS_LONG
    val buffer = Buffer()
        .writeByte(adapter.tagClass or adapter.tag.toInt())
        .writeByte(2)
        .writeByte(0b00000000)
        .writeByte(0b11111111)
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(255)
  }

  @Test fun `decode bigger than max long`() {
    val adapter = Adapters.INTEGER_AS_BIG_INTEGER
    val buffer = Buffer()
        .write("0209008000000000000001".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo(BigInteger("9223372036854775809"))
  }

  @Test fun `decode utf8 string`() {
    val adapter = Adapters.UTF8_STRING
    val buffer = Buffer()
        .write("0c04f09f988e".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo("\uD83D\uDE0E")
  }

  @Test fun `decode ia5`() {
    val adapter = Adapters.IA5_STRING
    val buffer = Buffer()
        .write("16026869".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo("hi")
  }

  @Test fun `decode printable string`() {
    val adapter = Adapters.PRINTABLE_STRING
    val buffer = Buffer()
        .write("13026869".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo("hi")
  }

  @Test fun `decode utc time`() {
    val adapter = Adapters.UTC_TIME
    val buffer = Buffer()
        .write("17113139313231353139303231302d30383030".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter))
        .isEqualTo(date("2019-12-16T03:02:10.000+0000").time)
  }

  @Test fun `decode generalized time`() {
    val adapter = Adapters.GENERALIZED_TIME
    val buffer = Buffer()
        .write("181332303139313231353139303231302d30383030".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter))
        .isEqualTo(date("2019-12-16T03:02:10.000+0000").time)
  }

  @Test fun `utc time two digit year cutoff is 1950`() {
    assertThat(Adapters.parseUtcTime("500101000000-0000"))
        .isEqualTo(date("1950-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseUtcTime("500101000000-0100"))
        .isEqualTo(date("1950-01-01T01:00:00.000+0000").time)

    assertThat(Adapters.parseUtcTime("491231235959+0100"))
        .isEqualTo(date("2049-12-31T22:59:59.000+0000").time)
    assertThat(Adapters.parseUtcTime("491231235959-0000"))
        .isEqualTo(date("2049-12-31T23:59:59.000+0000").time)

    // Note that time zone offsets aren't honored by Java's two-digit offset boundary! A savvy time
    // traveler could exploit this to get a certificate that expires 100 years later than expected.
    assertThat(Adapters.parseUtcTime("500101000000+0100"))
        .isEqualTo(date("2049-12-31T23:00:00.000+0000").time)
    assertThat(Adapters.parseUtcTime("491231235959-0100"))
        .isEqualTo(date("2050-01-01T00:59:59.000+0000").time)
  }

  @Test fun `generalized time`() {
    assertThat(Adapters.parseGeneralizedTime("18990101000000-0000"))
        .isEqualTo(date("1899-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("19500101000000-0000"))
        .isEqualTo(date("1950-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("20500101000000-0000"))
        .isEqualTo(date("2050-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("20990101000000-0000"))
        .isEqualTo(date("2099-01-01T00:00:00.000+0000").time)
  }

  @Test fun `decode object identifier`() {
    val adapter = Adapters.OBJECT_IDENTIFIER
    val buffer = Buffer()
        .write("06092a864886f70d01010b".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter))
        .isEqualTo("1.2.840.113549.1.1.11")
  }

  @Test fun `decode null`() {
    val adapter = Adapters.NULL
    val buffer = Buffer()
        .write("0500".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isNull()
  }

  @Test fun `decode sequence algorithm`() {
    val buffer = Buffer()
        .write("300d06092a864886f70d01010b0500".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(CertificateAdapters.algorithmIdentifier)).isEqualTo(
        AlgorithmIdentifier(
            algorithm = "1.2.840.113549.1.1.11",
            parameters = null
        )
    )
  }

  @Test fun `decode bit string`() {
    val adapter = Adapters.BIT_STRING
    val buffer = Buffer()
        .write("0304066e5dc0".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter))
        .isEqualTo(BitString("6e5dc0".decodeHex(), 6)
    )
  }

  @Test fun `decode octet string`() {
    val adapter = Adapters.OCTET_STRING
    val buffer = Buffer()
        .write("0404030206A0".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(adapter)).isEqualTo("030206A0".decodeHex())
  }

  @Test fun `decode choice rfc822`() {
    val buffer = Buffer()
        .write("810d61406578616d706c652e636f6d".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(GeneralName.ADAPTER))
        .isEqualTo(GeneralName.rfc822Name to "a@example.com")
  }

  @Test fun `decode choice dns`() {
    val buffer = Buffer()
        .write("820b6578616d706c652e636f6d".decodeHex())
    val derReader = DerReader(buffer)
    assertThat(derReader.read(GeneralName.ADAPTER))
        .isEqualTo(GeneralName.dNSName to "example.com")
  }

  private fun derAdapter(block: (Int, Long, Boolean, Long) -> Unit): DerAdapter<Unit> {
    return object : DerAdapter<Unit>(-1, -1L) {
      override fun decode(reader: DerReader, header: DerHeader) {
        return block(header.tagClass, header.tag, header.constructed, header.length)
      }
    }
  }

  /**
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
   */
  object GeneralName {
    val rfc822Name = Adapters.IA5_STRING.withTag(tag = 1L)
    val dNSName = Adapters.IA5_STRING.withTag(tag = 2L)
    val uniformResourceIdentifier = Adapters.IA5_STRING.withTag(tag = 6L)
    val iPAddress = Adapters.OCTET_STRING.withTag(tag = 7L)
    val registeredID = Adapters.OBJECT_IDENTIFIER.withTag(tag = 8L)
    val ADAPTER = Adapters.choice(
        rfc822Name,
        dNSName,
        uniformResourceIdentifier,
        iPAddress,
        registeredID
    )
  }

  /**
   * ```
   * Point ::= SEQUENCE {
   *   x [0] INTEGER OPTIONAL,
   *   y [1] INTEGER OPTIONAL
   * }
   * ```
   */
  data class Point(
    val x: Long?,
    val y: Long?
  ) {
    companion object {
      val ADAPTER = Adapters.sequence(
          Adapters.INTEGER_AS_LONG.withTag(tag = 0L).optional(),
          Adapters.INTEGER_AS_LONG.withTag(tag = 1L).optional()
      ) {
        Point(it[0] as Long?, it[1] as Long?)
      }
    }
  }

  private fun date(s: String): Date {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").apply {
      timeZone = TimeZone.getTimeZone("GMT")
    }
    return format.parse(s)
  }
}
