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

import okio.Buffer
import okio.ByteString

/**
 * Encode and decode a model object like a [Long] or [Certificate] as DER bytes.
 */
internal interface DerAdapter<T> {
  /** Returns true if this adapter can read [header] in a choice. */
  fun matches(header: DerHeader): Boolean

  /**
   * Returns a value from this adapter.
   *
   * This must always return a value, though it doesn't necessarily need to consume data from
   * [reader]. For example, if the reader's peeked tag isn't readable by this adapter, it may return
   * a default value.
   *
   * If this does read a value, it starts with the tag and length, and reads an entire value,
   * including any potential composed values.
   *
   * If there's nothing to read and no default value, this will throw an exception.
   */
  fun fromDer(reader: DerReader): T

  fun fromDer(byteString: ByteString): T {
    val buffer = Buffer().write(byteString)
    val reader = DerReader(buffer)
    return fromDer(reader)
  }

  /**
   * Writes [value] to this adapter, unless it is the default value and can be safely omitted.
   *
   * If this does write a value, it will write a tag and a length and a full value.
   */
  fun toDer(writer: DerWriter, value: T)

  fun toDer(value: T): ByteString {
    val buffer = Buffer()
    val writer = DerWriter(buffer)
    toDer(writer, value)
    return buffer.readByteString()
  }

  /**
   * Returns an adapter that expects this value wrapped by another value. Typically this occurs
   * when a value has both a context or application tag and a universal tag.
   *
   * Use this for EXPLICIT tag types:
   *
   * ```
   * [5] EXPLICIT UTF8String
   * ```
   *
   * @param forceConstructed non-null to set the constructed bit to the specified value, even if the
   *     writing process sets something else. This is used to encode SEQUENCES in values that are
   *     declared to have non-constructed values, like OCTET STRING values.
   */
  @Suppress("UNCHECKED_CAST") // read() produces a single element of the expected type.
  fun withExplicitBox(
    tagClass: Int = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC,
    tag: Long,
    forceConstructed: Boolean? = null
  ): BasicDerAdapter<T> {
    val codec = object : BasicDerAdapter.Codec<T> {
      override fun decode(reader: DerReader) = fromDer(reader)
      override fun encode(writer: DerWriter, value: T) {
        toDer(writer, value)
        if (forceConstructed != null) {
          writer.constructed = forceConstructed
        }
      }
    }

    return BasicDerAdapter(
        name = "EXPLICIT",
        tagClass = tagClass,
        tag = tag,
        codec = codec
    )
  }

  /** Returns an adapter that returns a list of values of this type. */
  fun asSequenceOf(
    name: String = "SEQUENCE OF",
    tagClass: Int = DerHeader.TAG_CLASS_UNIVERSAL,
    tag: Long = 16L
  ): BasicDerAdapter<List<T>> {
    val codec = object : BasicDerAdapter.Codec<List<T>> {
      override fun encode(writer: DerWriter, value: List<T>) {
        for (v in value) {
          toDer(writer, v)
        }
      }

      override fun decode(reader: DerReader): List<T> {
        val result = mutableListOf<T>()
        while (reader.hasNext()) {
          result += fromDer(reader)
        }
        return result
      }
    }

    return BasicDerAdapter(name, tagClass, tag, codec)
  }

  /** Returns an adapter that returns a set of values of this type. */
  fun asSetOf(): BasicDerAdapter<List<T>> {
    return asSequenceOf(
        name = "SET OF",
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 17L
    )
  }
}
