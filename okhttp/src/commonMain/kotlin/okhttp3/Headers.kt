/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package okhttp3

import okhttp3.Headers.Builder

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings; use `Request` and
 * `Response` for interpreted headers. This class maintains the order of the header fields within
 * the HTTP message.
 *
 * This class tracks header values line-by-line. A field with multiple comma- separated values on
 * the same line will be treated as a field with a single value by this class. It is the caller's
 * responsibility to detect and split on commas if their field permits multiple values. This
 * simplifies use of single-valued fields whose values routinely contain commas, such as cookies or
 * dates.
 *
 * This class trims whitespace from values. It never returns values with leading or trailing
 * whitespace.
 *
 * Instances of this class are immutable. Use [Builder] to create instances.
 */
@Suppress("NAME_SHADOWING")
expect class Headers internal constructor(
  namesAndValues: Array<String>
) : Iterable<Pair<String, String>> {
  internal val namesAndValues: Array<String>

  /** Returns the last value corresponding to the specified field, or null. */
  operator fun get(name: String): String?

  /** Returns the number of field values. */
  val size: Int

  /** Returns the field at `position`. */
  fun name(index: Int): String

  /** Returns the value at `index`. */
  fun value(index: Int): String

  /** Returns an immutable case-insensitive set of header names. */
  fun names(): Set<String>

  /** Returns an immutable list of the header values for `name`. */
  fun values(name: String): List<String>

  override operator fun iterator(): Iterator<Pair<String, String>>

  fun newBuilder(): Builder

  /**
   * Returns true if `other` is a `Headers` object with the same headers, with the same casing, in
   * the same order. Note that two headers instances may be *semantically* equal but not equal
   * according to this method. In particular, none of the following sets of headers are equal
   * according to this method:
   *
   * 1. Original
   * ```
   * Content-Type: text/html
   * Content-Length: 50
   * ```
   *
   * 2. Different order
   *
   * ```
   * Content-Length: 50
   * Content-Type: text/html
   * ```
   *
   * 3. Different case
   *
   * ```
   * content-type: text/html
   * content-length: 50
   * ```
   *
   * 4. Different values
   *
   * ```
   * Content-Type: text/html
   * Content-Length: 050
   * ```
   *
   * Applications that require semantically equal headers should convert them into a canonical form
   * before comparing them for equality.
   */
  override fun equals(other: Any?): Boolean

  /**
   * Returns header names and values. The names and values are separated by `: ` and each pair is
   * followed by a newline character `\n`.
   *
   * Since OkHttp 5 this redacts these sensitive headers:
   *
   *  * `Authorization`
   *  * `Cookie`
   *  * `Proxy-Authorization`
   *  * `Set-Cookie`
   */
  override fun toString(): String

  class Builder internal constructor() {
    internal val namesAndValues: MutableList<String>

    /**
     * Add a header with the specified name and value. Does validation of header names and values.
     */
    fun add(name: String, value: String): Builder

    /**
     * Adds all headers from an existing collection.
     */
    fun addAll(headers: Headers): Builder

    fun removeAll(name: String): Builder

    /**
     * Set a field with the specified value. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    operator fun set(name: String, value: String): Builder

    /** Equivalent to `build().get(name)`, but potentially faster. */
    operator fun get(name: String): String?

    fun build(): Headers
  }

  companion object {
    /**
     * Returns headers for the alternating header names and values. There must be an even number of
     * arguments, and they must alternate between header names and values.
     */
    fun headersOf(vararg namesAndValues: String): Headers

    /** Returns headers for the header names and values in the [Map]. */
    fun Map<String, String>.toHeaders(): Headers
  }
}
