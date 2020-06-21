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

import okio.IOException

/**
 * Reads a DER tag class, tag, length, and value and decodes it as value.
 */
internal abstract class DerAdapter<T>(
  /** The tag class this adapter expects, or -1 to match any tag class. */
  val tagClass: Int,

  /** The tag this adapter expects, or -1 to match any tag. */
  val tag: Long,

  /** True if the default value should be used if this value is absent during decoding. */
  val isOptional: Boolean = false,

  /** The value to return if this value is absent. Undefined unless this is optional. */
  val defaultValue: T? = null
) {
  /**
   * Returns true if this adapter decodes values with [tagClass] and [tag]. Most adapters match
   * exactly one tag class and tag, but ANY adapters match anything and CHOICE adapters match
   * multiple.
   */
  open fun matches(tagClass: Int, tag: Long): Boolean {
    if (tagClass == -1 || tag == -1L) return false

    return (this.tagClass == -1 || tagClass == this.tagClass) &&
        (this.tag == -1L || tag == this.tag)
  }

  abstract fun decode(reader: DerReader, header: DerHeader): T

  private fun copy(
    tagClass: Int = this.tagClass,
    tag: Long = this.tag,
    isOptional: Boolean = this.isOptional,
    defaultValue: T? = this.defaultValue
  ): DerAdapter<T> = object : DerAdapter<T>(tagClass, tag, isOptional, defaultValue) {
    override fun decode(reader: DerReader, header: DerHeader): T {
      return this@DerAdapter.decode(reader, header)
    }
  }

  /**
   * Returns a copy with a context tag. This should be used when the type is ambiguous on its own.
   * For example, the tags in this schema are 0 and 1:
   *
   * ```
   * Point ::= SEQUENCE {
   *   x [0] INTEGER OPTIONAL,
   *   y [1] INTEGER OPTIONAL
   * }
   * ```
   *
   * You may also specify a tag class like [DerHeader.TAG_CLASS_APPLICATION]. The default tag class
   * is [DerHeader.TAG_CLASS_CONTEXT_SPECIFIC].
   *
   * ```
   * Point ::= SEQUENCE {
   *   x [APPLICATION 0] INTEGER OPTIONAL,
   *   y [APPLICATION 1] INTEGER OPTIONAL
   * }
   * ```
   */
  fun withTag(tagClass: Int = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC, tag: Long): DerAdapter<T> {
    return copy(
        tagClass = tagClass,
        tag = tag
    )
  }

  /** Returns a copy of this adapter that doesn't encode values equal to [defaultValue]. */
  fun optional(defaultValue: T? = null): DerAdapter<T> {
    return copy(
        isOptional = true,
        defaultValue = defaultValue
    )
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
   */
  @Suppress("UNCHECKED_CAST") // read() produces a single element of the expected type.
  fun withExplicitBox(
    tagClass: Int = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC,
    tag: Long
  ): DerAdapter<T> {
    return Adapters.sequence(this) { list -> list[0] as T }
        .withTag(tagClass, tag)
  }

  /** Returns an adapter that returns a list of values of this type. */
  fun asSequenceOf(
    tagClass: Int = DerHeader.TAG_CLASS_UNIVERSAL,
    tag: Long = 16L
  ): DerAdapter<List<T>> {
    val member = this
    return object : DerAdapter<List<T>>(
        tagClass = tagClass,
        tag = tag
    ) {
      override fun decode(reader: DerReader, header: DerHeader): List<T> {
        val result = mutableListOf<T>()

        while (reader.hasNext()) {
          if (!member.matches(reader.peekedTagClass, reader.peekedTag)) {
            throw IOException("expected ${member.tagClass}/${member.tag} but was $tagClass/$tag")
          }
          result += reader.read(member)
        }

        return result
      }
    }
  }

  /** Returns an adapter that returns a set of values of this type. */
  fun asSetOf(): DerAdapter<List<T>> {
    return asSequenceOf(
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 17L
    )
  }

  override fun toString() = "$tagClass/$tag"
}
