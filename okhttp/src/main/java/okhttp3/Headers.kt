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
import okhttp3.internal.format
import okhttp3.internal.http.toHttpDateOrNull
import okhttp3.internal.http.toHttpDateString
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import java.time.Instant
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet

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
class Headers private constructor(
  private val namesAndValues: Array<String>
) : Iterable<Pair<String, String>> {
  /** Returns the last value corresponding to the specified field, or null. */
  operator fun get(name: String): String? = get(namesAndValues, name)

  /**
   * Returns the last value corresponding to the specified field parsed as an HTTP date, or null if
   * either the field is absent or cannot be parsed as a date.
   */
  fun getDate(name: String): Date? = get(name)?.toHttpDateOrNull()

  /**
   * Returns the last value corresponding to the specified field parsed as an HTTP date, or null if
   * either the field is absent or cannot be parsed as a date.
   */
  @IgnoreJRERequirement
  fun getInstant(name: String): Instant? {
    val value = getDate(name)
    return value?.toInstant()
  }

  /** Returns the number of field values. */
  @get:JvmName("size") val size: Int
    get() = namesAndValues.size / 2

  @JvmName("-deprecated_size")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "size"),
      level = DeprecationLevel.ERROR)
  fun size(): Int = size

  /** Returns the field at `position`. */
  fun name(index: Int): String = namesAndValues[index * 2]

  /** Returns the value at `index`. */
  fun value(index: Int): String = namesAndValues[index * 2 + 1]

  /** Returns an immutable case-insensitive set of header names. */
  fun names(): Set<String> {
    val result = TreeSet(String.CASE_INSENSITIVE_ORDER)
    for (i in 0 until size) {
      result.add(name(i))
    }
    return Collections.unmodifiableSet(result)
  }

  /** Returns an immutable list of the header values for `name`. */
  fun values(name: String): List<String> {
    var result: MutableList<String>? = null
    for (i in 0 until size) {
      if (name.equals(name(i), ignoreCase = true)) {
        if (result == null) result = ArrayList(2)
        result.add(value(i))
      }
    }
    return if (result != null) {
      Collections.unmodifiableList(result)
    } else {
      emptyList()
    }
  }

  /**
   * Returns the number of bytes required to encode these headers using HTTP/1.1. This is also the
   * approximate size of HTTP/2 headers before they are compressed with HPACK. This value is
   * intended to be used as a metric: smaller headers are more efficient to encode and transmit.
   */
  fun byteCount(): Long {
    // Each header name has 2 bytes of overhead for ': ' and every header value has 2 bytes of
    // overhead for '\r\n'.
    var result = (namesAndValues.size * 2).toLong()

    for (i in 0 until namesAndValues.size) {
      result += namesAndValues[i].length.toLong()
    }

    return result
  }

  override operator fun iterator(): Iterator<Pair<String, String>> {
    return Array(size) { name(it) to value(it) }.iterator()
  }

  fun newBuilder(): Builder {
    val result = Builder()
    result.namesAndValues += namesAndValues
    return result
  }

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
  override fun equals(other: Any?): Boolean {
    return other is Headers && namesAndValues.contentEquals(other.namesAndValues)
  }

  override fun hashCode(): Int = namesAndValues.contentHashCode()

  override fun toString(): String {
    return buildString {
      for (i in 0 until size) {
        append(name(i))
        append(": ")
        append(value(i))
        append("\n")
      }
    }
  }

  fun toMultimap(): Map<String, List<String>> {
    val result = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
    for (i in 0 until size) {
      val name = name(i).toLowerCase(Locale.US)
      var values: MutableList<String>? = result[name]
      if (values == null) {
        values = ArrayList(2)
        result[name] = values
      }
      values.add(value(i))
    }
    return result
  }

  class Builder {
    internal val namesAndValues: MutableList<String> = ArrayList(20)

    /**
     * Add a header line without any validation. Only appropriate for headers from the remote peer
     * or cache.
     */
    internal fun addLenient(line: String) = apply {
      val index = line.indexOf(':', 1)
      when {
        index != -1 -> {
          addLenient(line.substring(0, index), line.substring(index + 1))
        }
        line[0] == ':' -> {
          // Work around empty header names and header names that start with a colon (created by old
          // broken SPDY versions of the response cache).
          addLenient("", line.substring(1)) // Empty header name.
        }
        else -> {
          // No header name.
          addLenient("", line)
        }
      }
    }

    /** Add an header line containing a field name, a literal colon, and a value. */
    fun add(line: String) = apply {
      val index = line.indexOf(':')
      require(index != -1) { "Unexpected header: $line" }
      add(line.substring(0, index).trim(), line.substring(index + 1))
    }

    /**
     * Add a header with the specified name and value. Does validation of header names and values.
     */
    fun add(name: String, value: String) = apply {
      checkName(name)
      checkValue(value, name)
      addLenient(name, value)
    }

    /**
     * Add a header with the specified name and value. Does validation of header names, allowing
     * non-ASCII values.
     */
    fun addUnsafeNonAscii(name: String, value: String) = apply {
      checkName(name)
      addLenient(name, value)
    }

    /**
     * Adds all headers from an existing collection.
     */
    fun addAll(headers: Headers) = apply {
      for (i in 0 until headers.size) {
        addLenient(headers.name(i), headers.value(i))
      }
    }

    /**
     * Add a header with the specified name and formatted date. Does validation of header names and
     * value.
     */
    fun add(name: String, value: Date) = apply {
      add(name, value.toHttpDateString())
    }

    /**
     * Add a header with the specified name and formatted instant. Does validation of header names
     * and value.
     */
    @IgnoreJRERequirement
    fun add(name: String, value: Instant) = apply {
      add(name, Date(value.toEpochMilli()))
    }

    /**
     * Set a field with the specified date. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    operator fun set(name: String, value: Date) = apply {
      set(name, value.toHttpDateString())
    }

    /**
     * Set a field with the specified instant. If the field is not found, it is added. If the field
     * is found, the existing values are replaced.
     */
    @IgnoreJRERequirement
    operator fun set(name: String, value: Instant) = apply {
      return set(name, Date(value.toEpochMilli()))
    }

    /**
     * Add a field with the specified value without any validation. Only appropriate for headers
     * from the remote peer or cache.
     */
    internal fun addLenient(name: String, value: String) = apply {
      namesAndValues.add(name)
      namesAndValues.add(value.trim())
    }

    fun removeAll(name: String) = apply {
      var i = 0
      while (i < namesAndValues.size) {
        if (name.equals(namesAndValues[i], ignoreCase = true)) {
          namesAndValues.removeAt(i) // name
          namesAndValues.removeAt(i) // value
          i -= 2
        }
        i += 2
      }
    }

    /**
     * Set a field with the specified value. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    operator fun set(name: String, value: String) = apply {
      checkName(name)
      checkValue(value, name)
      removeAll(name)
      addLenient(name, value)
    }

    /** Equivalent to `build().get(name)`, but potentially faster. */
    operator fun get(name: String): String? {
      for (i in namesAndValues.size - 2 downTo 0 step 2) {
        if (name.equals(namesAndValues[i], ignoreCase = true)) {
          return namesAndValues[i + 1]
        }
      }
      return null
    }

    fun build(): Headers = Headers(namesAndValues.toTypedArray())
  }

  companion object {
    private fun get(namesAndValues: Array<String>, name: String): String? {
      for (i in namesAndValues.size - 2 downTo 0 step 2) {
        if (name.equals(namesAndValues[i], ignoreCase = true)) {
          return namesAndValues[i + 1]
        }
      }
      return null
    }

    /**
     * Returns headers for the alternating header names and values. There must be an even number of
     * arguments, and they must alternate between header names and values.
     */
    @JvmStatic
    @JvmName("of")
    fun headersOf(vararg namesAndValues: String): Headers {
      require(namesAndValues.size % 2 == 0) { "Expected alternating header names and values" }

      // Make a defensive copy and clean it up.
      val namesAndValues: Array<String> = namesAndValues.clone() as Array<String>
      for (i in namesAndValues.indices) {
        require(namesAndValues[i] != null) { "Headers cannot be null" }
        namesAndValues[i] = namesAndValues[i].trim()
      }

      // Check for malformed headers.
      for (i in 0 until namesAndValues.size step 2) {
        val name = namesAndValues[i]
        val value = namesAndValues[i + 1]
        checkName(name)
        checkValue(value, name)
      }

      return Headers(namesAndValues)
    }

    @JvmName("-deprecated_of")
    @Deprecated(
        message = "function name changed",
        replaceWith = ReplaceWith(expression = "headersOf(*namesAndValues)"),
        level = DeprecationLevel.ERROR)
    fun of(vararg namesAndValues: String): Headers {
      return headersOf(*namesAndValues)
    }

    /** Returns headers for the header names and values in the [Map]. */
    @JvmStatic
    @JvmName("of")
    fun Map<String, String>.toHeaders(): Headers {
      // Make a defensive copy and clean it up.
      val namesAndValues = arrayOfNulls<String>(size * 2)
      var i = 0
      for ((k, v) in this) {
        val name = k.trim()
        val value = v.trim()
        checkName(name)
        checkValue(value, name)
        namesAndValues[i] = name
        namesAndValues[i + 1] = value
        i += 2
      }

      return Headers(namesAndValues as Array<String>)
    }

    @JvmName("-deprecated_of")
    @Deprecated(
        message = "function moved to extension",
        replaceWith = ReplaceWith(expression = "headers.toHeaders()"),
        level = DeprecationLevel.ERROR)
    fun of(headers: Map<String, String>): Headers {
      return headers.toHeaders()
    }

    private fun checkName(name: String) {
      require(name.isNotEmpty()) { "name is empty" }
      for (i in 0 until name.length) {
        val c = name[i]
        require(c in '\u0021'..'\u007e') {
          format("Unexpected char %#04x at %d in header name: %s", c.toInt(), i, name)
        }
      }
    }

    private fun checkValue(value: String, name: String) {
      for (i in 0 until value.length) {
        val c = value[i]
        require(c == '\t' || c in '\u0020'..'\u007e') {
          format("Unexpected char %#04x at %d in %s value: %s", c.toInt(), i, name, value)
        }
      }
    }
  }
}
