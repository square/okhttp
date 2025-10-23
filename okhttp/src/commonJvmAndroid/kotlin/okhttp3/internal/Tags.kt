/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3.internal

import kotlin.reflect.KClass

/**
 * An immutable collection of key-value pairs implemented as a singly-linked list.
 *
 * Build up a collection by starting with [EmptyTags] and repeatedly calling [plus]. Each such call
 * returns a new instance.
 *
 * This collection is optimized for safe concurrent access over a very small number of elements.
 *
 * This collection and is expected to hold fewer than 10 elements. Each operation is _O(N)_, and so
 * building an instance with _N_ elements is _O(N**2)_.
 */
internal sealed class Tags {
  /**
   * Returns a tags instance that maps [key] to [value]. If [value] is null, this returns a tags
   * instance that does not have any mapping for [key].
   */
  abstract fun plus(
    key: KClass<*>,
    value: Any?,
  ): Tags

  abstract operator fun <T : Any> get(key: KClass<T>): T?
}

/** An empty tags. This is always the tail of a [LinkedTags] chain. */
internal object EmptyTags : Tags() {
  override fun plus(
    key: KClass<*>,
    value: Any?,
  ): Tags =
    when {
      value != null -> LinkedTags(key, value, this)
      else -> this
    }

  override fun <T : Any> get(key: KClass<T>): T? = null

  override fun toString() = "{}"
}

/**
 * An invariant of this implementation is that [next] must not contain a mapping for [key].
 * Otherwise, we would have two values for the same key.
 */
private class LinkedTags(
  private val key: KClass<*>,
  private val value: Any,
  private val next: Tags,
) : Tags() {
  override fun plus(
    key: KClass<*>,
    value: Any?,
  ): Tags {
    // Create a copy of this `LinkedTags` that doesn't have a mapping for `key`.
    val thisMinusKey =
      when {
        key == this.key -> next // Subtract this!

        else -> {
          val nextMinusKey = next.plus(key, null)
          when {
            nextMinusKey === next -> this // Same as the following line, but with fewer allocations.
            else -> LinkedTags(this.key, this.value, nextMinusKey)
          }
        }
      }

    // Return a new `Tags` that maps `key` to `value`.
    return when {
      value != null -> LinkedTags(key, value, thisMinusKey)
      else -> thisMinusKey
    }
  }

  override fun <T : Any> get(key: KClass<T>): T? =
    when {
      key == this.key -> key.java.cast(value)
      else -> next[key]
    }

  /** Returns a [toString] consistent with [Map], with elements in insertion order. */
  override fun toString(): String =
    generateSequence(seed = this) { it.next as? LinkedTags }
      .toList()
      .reversed()
      .joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
}
