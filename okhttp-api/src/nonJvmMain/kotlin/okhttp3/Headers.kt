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

import okhttp3.internal.commonAdd
import okhttp3.internal.commonAddAll
import okhttp3.internal.commonAddLenient
import okhttp3.internal.commonBuild
import okhttp3.internal.commonEquals
import okhttp3.internal.commonGet
import okhttp3.internal.commonHashCode
import okhttp3.internal.commonHeadersGet
import okhttp3.internal.commonHeadersOf
import okhttp3.internal.commonIterator
import okhttp3.internal.commonName
import okhttp3.internal.commonNewBuilder
import okhttp3.internal.commonRemoveAll
import okhttp3.internal.commonSet
import okhttp3.internal.commonToHeaders
import okhttp3.internal.commonToString
import okhttp3.internal.commonValue
import okhttp3.internal.commonValues

@Suppress("NAME_SHADOWING")
actual class Headers internal actual constructor(
  internal actual val namesAndValues: Array<String>
) : Iterable<Pair<String, String>> {
  actual operator fun get(name: String): String? = commonHeadersGet(namesAndValues, name)

  actual val size: Int
    get() = namesAndValues.size / 2

  actual fun name(index: Int): String = commonName(index)

  actual fun value(index: Int): String = commonValue(index)

  actual fun names(): Set<String> {
    return (0 until size).map { name(it) }.distinctBy { it.lowercase() }.toSet()
  }

  actual fun values(name: String): List<String> = commonValues(name)

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

  actual override operator fun iterator(): Iterator<Pair<String, String>> = commonIterator()

  actual fun newBuilder(): Builder = commonNewBuilder()

  actual override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  actual override fun toString(): String = commonToString()

  actual class Builder {
    internal actual val namesAndValues: MutableList<String> = ArrayList(20)

    actual fun add(name: String, value: String) = commonAdd(name, value)

    actual fun addAll(headers: Headers) = commonAddAll(headers)

    actual fun removeAll(name: String) = commonRemoveAll(name)

    /**
     * Set a field with the specified value. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    actual operator fun set(name: String, value: String) = commonSet(name, value)

    /** Equivalent to `build().get(name)`, but potentially faster. */
    actual operator fun get(name: String): String? = commonGet(name)

    actual fun build(): Headers = commonBuild()
  }

  actual companion object {
    actual fun headersOf(vararg namesAndValues: String): Headers = commonHeadersOf(*namesAndValues)

    actual fun Map<String, String>.toHeaders(): Headers = commonToHeaders()
  }
}
