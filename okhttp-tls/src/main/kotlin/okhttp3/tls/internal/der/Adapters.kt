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
import kotlin.reflect.KClass
import okio.ByteString

/**
 * Built-in adapters for reading standard ASN.1 types.
 */
internal object Adapters {
  val BOOLEAN = object : DerAdapter<Boolean>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 1L
  ) {
    override fun encode(writer: DerWriter, value: Boolean) = writer.writeBoolean(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readBoolean()
  }

  val INTEGER_AS_LONG = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L
  ) {
    override fun encode(writer: DerWriter, value: Long) = writer.writeLong(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readLong()
  }

  val INTEGER_AS_BIG_INTEGER = object : DerAdapter<BigInteger>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L
  ) {
    override fun encode(writer: DerWriter, value: BigInteger) = writer.writeBigInteger(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readBigInteger()
  }

  val BIT_STRING = object : DerAdapter<BitString>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 3L
  ) {
    override fun encode(writer: DerWriter, value: BitString) = writer.writeBitString(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readBitString()
  }

  val OCTET_STRING = object : DerAdapter<ByteString>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 4L
  ) {
    override fun encode(writer: DerWriter, value: ByteString) = writer.writeOctetString(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readOctetString()
  }

  val NULL = object : DerAdapter<Unit?>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 5L
  ) {
    override fun encode(writer: DerWriter, value: Unit?) {
    }

    override fun decode(reader: DerReader, header: DerHeader): Unit? = null
  }

  val OBJECT_IDENTIFIER = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 6L
  ) {
    override fun encode(writer: DerWriter, value: String) = writer.writeObjectIdentifier(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readObjectIdentifier()
  }

  val UTF8_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 12L
  ) {
    override fun encode(writer: DerWriter, value: String) = writer.writeUtf8(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * Permits alphanumerics, spaces, and these:
   *
   * ```
   *   ' () + , - . / : = ?
   * ```
   */
  // TODO(jwilson): constrain to printable string characters.
  val PRINTABLE_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 19L
  ) {
    override fun encode(writer: DerWriter, value: String) = writer.writeUtf8(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * Based on International Alphabet No. 5. Note that there are bytes that IA5 and US-ASCII
   * disagree on interpretation.
   */
  // TODO(jwilson): constrain to IA5 characters.
  val IA5_STRING = object : DerAdapter<String>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 22L
  ) {
    override fun encode(writer: DerWriter, value: String) = writer.writeUtf8(value)

    override fun decode(reader: DerReader, header: DerHeader) = reader.readUtf8String()
  }

  /**
   * A timestamp like "191216030210Z" or "191215190210-0800" for 2019-12-15T19:02:10-08:00. The
   * cutoff of the 2-digit year is 1950-01-01T00:00:00Z.
   */
  val UTC_TIME = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 23L
  ) {
    override fun encode(writer: DerWriter, value: Long) {
      val string = formatUtcTime(value)
      writer.writeUtf8(string)
    }

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

  internal fun formatUtcTime(date: Long): String {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat = SimpleDateFormat("yyMMddHHmmssXX").apply {
      timeZone = utc
      set2DigitYearStart(Date(-631152000000L)) // 1950-01-01T00:00:00Z.
    }

    return dateFormat.format(date)
  }

  /**
   * A timestamp like "191216030210Z" or "20191215190210-0800" for 2019-12-15T19:02:10-08:00. This
   * is the same as [UTC_TIME] with the exception of the 4-digit year.
   */
  val GENERALIZED_TIME = object : DerAdapter<Long>(
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 24L
  ) {
    override fun encode(writer: DerWriter, value: Long) {
      val string = formatGeneralizedTime(value)
      writer.writeUtf8(string)
    }

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

  internal fun formatGeneralizedTime(date: Long): String {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat = SimpleDateFormat("yyyyMMddHHmmssXX").apply {
      timeZone = utc
    }

    return dateFormat.format(date)
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
  fun <T> sequence(
    vararg members: DerAdapter<*>,
    decompose: (T) -> List<*>,
    construct: (List<*>) -> T
  ): DerAdapter<T> {
    return object : DerAdapter<T>(
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 16L
    ) {
      override fun encode(writer: DerWriter, value: T) {
        writer.pushTypeHint()
        try {
          encodeWithTypeHints(value, writer)
        } finally {
          writer.popTypeHint()
        }
      }

      private fun encodeWithTypeHints(value: T, writer: DerWriter) {
        val list = decompose(value)

        for (i in list.indices) {
          val v = list[i]
          val adapter = members[i] as DerAdapter<Any?>

          if (adapter.typeHint) {
            writer.typeHint = v
          }

          if (adapter.omitInSequence(v)) {
            // Skip.
          } else {
            writer.write(adapter, v)
          }
        }
      }

      override fun decode(reader: DerReader, header: DerHeader): T {
        reader.pushTypeHint()
        try {
          return decodeWithTypeHints(reader)
        } finally {
          reader.popTypeHint()
        }
      }

      private fun decodeWithTypeHints(reader: DerReader): T {
        val list = mutableListOf<Any?>()

        while (list.size < members.size) {
          val member = members[list.size]

          val value = when {
            reader.hasNext() &&
                member.matches(reader.peekedTagClass, reader.peekedTag) -> {
              reader.read(member)
            }
            member.isOptional -> {
              member.defaultValue
            }
            else -> {
              throw IOException("expected ${member.tagClass}/${member.tag} " +
                  "but was ${reader.peekedTagClass}/${reader.peekedTag}")
            }
          }

          if (member.typeHint) {
            reader.typeHint = value
          }
          list += value
        }

        if (reader.hasNext()) {
          throw IOException("unexpected ${reader.peekedTagClass}/${reader.peekedTag}")
        }

        return construct(list)
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

      override fun encode(writer: DerWriter, value: Pair<DerAdapter<*>, Any?>) {
        val (adapter, v) = value
        writer.write(adapter as DerAdapter<Any?>, v)
      }

      override fun decode(reader: DerReader, header: DerHeader): Pair<DerAdapter<*>, Any?> {
        val choice = choices.firstOrNull { it.matches(header.tagClass, header.tag) }
            ?: throw IOException(
                "expected a matching choice but was ${header.tagClass}/${header.tag}")

        return choice to choice.decode(reader, header)
      }
    }
  }

  /**
   * This decodes an [OCTET_STRING] value into its contents, which are also expected to be ASN.1.
   * To determine which type to decode as it uses a preceding member of the same SEQUENCE. For
   * example, extensions type IDs specify what types to use for the corresponding values.
   *
   * If the hint is unknown [chooser] should return null which will cause the value to be decoded as
   * an opaque byte string.
   */
  fun usingTypeHint(chooser: (Any?) -> DerAdapter<*>?): DerAdapter<Any?> {
    return object : DerAdapter<Any?>(tagClass = OCTET_STRING.tagClass, tag = OCTET_STRING.tag) {
      override fun encode(writer: DerWriter, value: Any?) {
        val adapter = chooser(writer.typeHint)

        // If we don't understand this hint, encode the body as a byte string. The byte string
        // will include a tag and length header as a prefix.
        if (adapter == null) {
          (OCTET_STRING as DerAdapter<Any?>).encode(writer, value)
          return
        }

        writer.write(adapter as DerAdapter<Any?>, value)
      }

      override fun decode(reader: DerReader, header: DerHeader): Any? {
        val adapter = chooser(reader.typeHint)

        // If we don't understand this hint, decode the body as a byte string. The byte string
        // will include a tag and length header as a prefix.
        if (adapter == null) {
          return OCTET_STRING.decode(reader, header)
        }

        return reader.read(adapter as DerAdapter<Any?>)
      }
    }
  }

  /**
   * Object class to adapter type. This approach limits us to one adapter per Kotlin class, which
   * might be too few for values like UTF_STRING and OBJECT_IDENTIFIER that share a Kotlin class but
   * have very different ASN.1 interpretations.
   */
  private val defaultAnyChoices = listOf(
      Boolean::class to BOOLEAN,
      BigInteger::class to INTEGER_AS_BIG_INTEGER,
      BitString::class to BIT_STRING,
      ByteString::class to OCTET_STRING,
      Unit::class to NULL,
      Nothing::class to OBJECT_IDENTIFIER,
      String::class to UTF8_STRING,
      Nothing::class to PRINTABLE_STRING,
      Nothing::class to IA5_STRING,
      Nothing::class to UTC_TIME,
      Long::class to GENERALIZED_TIME
  )

  fun any(
    vararg choices: Pair<KClass<*>, DerAdapter<*>> = defaultAnyChoices.toTypedArray()
  ): DerAdapter<Any?> {
    return object : DerAdapter<Any?>(
        tagClass = -1,
        tag = -1L
    ) {
      override fun encode(writer: DerWriter, value: Any?) {
        if (value is AnyValue) {
          writer.write(withTag(value.tagClass, value.tag)) {
            writer.writeOctetString(value.bytes)
          }
        } else {
          for ((type, adapter) in choices) {
            if (type.isInstance(value) || (value == null && type == Unit::class)) {
              writer.write(adapter as DerAdapter<Any?>, value)
              return
            }
          }
        }
      }

      override fun decode(reader: DerReader, header: DerHeader): Any? {
        for ((_, adapter) in choices) {
          if (adapter.matches(header.tagClass, header.tag)) {
            return adapter.decode(reader, header)
          }
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
