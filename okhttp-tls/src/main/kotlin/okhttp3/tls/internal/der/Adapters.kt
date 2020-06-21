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

import java.io.IOException
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import okio.ByteString

/**
 * Built-in adapters for reading standard ASN.1 types.
 */
internal object Adapters {
  val BOOLEAN = object : DerAdapter<Boolean>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 1L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readBoolean()
  }

  val INTEGER_AS_LONG = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readLong()
  }

  val INTEGER_AS_BIG_INTEGER = object : DerAdapter<BigInteger>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readBigInteger()
  }

  val BIT_STRING = object : DerAdapter<BitString>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 3L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readBitString()
  }

  val OCTET_STRING = object : DerAdapter<ByteString>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 4L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readOctetString()
  }

  val NULL = object : DerAdapter<Unit?>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 5L
  ) {
    override fun decode(reader: DerReader, header: DerHeader): Unit? = null
  }

  val OBJECT_IDENTIFIER = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 6L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readObjectIdentifier()
  }

  val UTF8_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 12L
  ) {
    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * Permits alphanumerics, spaces, and these:
   *
   * ```
   *   ' () + , - . / : = ?
   * ```
   */
  val PRINTABLE_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 19L
  ) {
    // TODO(jwilson): constrain to printable string characters.
    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * Based on International Alphabet No. 5. Note that there are bytes that IA5 and US-ASCII
   * disagree on interpretation.
   */
  val IA5_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 22L
  ) {
    // TODO(jwilson): constrain to IA5 characters.
    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * A timestamp like "191215190210-0800" for 2019-12-15T19:02:10-08:00. The cutoff of the 2-digit
   * year is 1950-01-01T00:00:00Z.
   */
  val UTC_TIME = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 23L
  ) {
    override fun decode(reader: DerReader, header: DerHeader): Long {
      val string = reader.readUtf8String()
      return parseUtcTime(string)
    }
  }

  internal fun parseUtcTime(string: String): Long {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat = SimpleDateFormat("yyMMddHHmmssXX").apply {
      timeZone = utc
      set2DigitYearStart(Date(-631152000000L)) // 1950-01-01T00:00:00Z.
    }

    val parsed = dateFormat.parse(string)
    return parsed.time
  }

  /**
   * A timestamp like "20191215190210-0800" for 2019-12-15T19:02:10-08:00. This is the same as
   * [UTC_TIME] with the exception of the 4-digit year.
   */
  val GENERALIZED_TIME = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 24L
  ) {
    override fun decode(reader: DerReader, header: DerHeader): Long {
      val string = reader.readUtf8String()
      return parseGeneralizedTime(string)
    }
  }

  internal fun parseGeneralizedTime(string: String): Long {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat = SimpleDateFormat("yyyyMMddHHmmssXX").apply {
      timeZone = utc
    }

    val parsed = dateFormat.parse(string)
    return parsed.time
  }

  /**
   * Returns a composite adapter for a struct or data class. This may be used for both SEQUENCE and
   * SET types.
   *
   * The fields are specified as a list of member adapters. When decoding, a value for each
   * non-optional member but be included in sequence.
   *
   * TODO: for sets, sort by tag when encoding.
   * TODO: for set ofs, sort by encoded value when encoding.
   */
  fun <T> sequence(vararg members: DerAdapter<*>, constructor: (List<*>) -> T): DerAdapter<T> {
    return object : DerAdapter<T>(
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 16L
    ) {
      override fun decode(reader: DerReader, header: DerHeader): T {
        val list = mutableListOf<Any?>()

        while (list.size < members.size) {
          val member = members[list.size]
          if (reader.hasNext() && member.matches(reader.peekedTagClass, reader.peekedTag)) {
            list += reader.read(member)
          } else if (member.isOptional) {
            list += member.defaultValue
          } else {
            throw IOException("expected ${member.tagClass}/${member.tag} " +
                "but was ${reader.peekedTagClass}/${reader.peekedTag}")
          }
        }

        if (reader.hasNext()) {
          throw IOException("unexpected ${reader.peekedTagClass}/${reader.peekedTag}")
        }

        return constructor(list)
      }
    }
  }

  /** Returns an adapter that decodes as the first of a list of available types. */
  fun choice(vararg choices: DerAdapter<*>): DerAdapter<Pair<DerAdapter<*>, Any?>> {
    return object : DerAdapter<Pair<DerAdapter<*>, Any?>>(
        tagClass = -1,
        tag = -1L
    ) {
      override fun matches(tagClass: Int, tag: Long) = choices.any { it.matches(tagClass, tag) }

      override fun decode(reader: DerReader, header: DerHeader): Pair<DerAdapter<*>, Any?> {
        val choice = choices.firstOrNull { it.matches(header.tagClass, header.tag) }
            ?: throw IOException(
                "expected a matching choice but was ${header.tagClass}/${header.tag}")

        return choice to choice.decode(reader, header)
      }
    }
  }

  private val defaultAnyChoices = listOf(
      BOOLEAN,
      INTEGER_AS_BIG_INTEGER,
      BIT_STRING,
      OCTET_STRING,
      NULL,
      OBJECT_IDENTIFIER,
      UTF8_STRING,
      PRINTABLE_STRING,
      IA5_STRING,
      UTC_TIME,
      GENERALIZED_TIME
  )

  fun any(vararg choices: DerAdapter<*> = defaultAnyChoices.toTypedArray()): DerAdapter<Any?> {
    return object : DerAdapter<Any?>(
        tagClass = -1,
        tag = -1L
    ) {
      val delegate = choice(*choices)

      override fun decode(reader: DerReader, header: DerHeader): Any? {
        if (delegate.matches(header.tagClass, header.tag)) {
          val (adapter, value) = delegate.decode(reader, header)
          return value
        }

        val bytes = reader.readOctetString()
        return AnyValue(
            tagClass = header.tagClass,
            tag = header.tag,
            constructed = header.constructed,
            length = header.length,
            bytes = bytes
        )
      }
    }
  }
}
