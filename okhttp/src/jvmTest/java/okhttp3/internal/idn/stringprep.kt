/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.internal.idn

import okio.Buffer
import okio.BufferedSource

/**
 * An implementation of Stringprep ([RFC 3454]) intended for use with Nameprep ([RFC 3491]).
 *
 * [RFC 3454]: https://datatracker.ietf.org/doc/html/rfc3454
 * [RFC 3491]: https://datatracker.ietf.org/doc/html/rfc3491
 */
class Stringprep(
  /** Unassigned code points. */
  val unassigned: CodePointSet,
  /** Mappings. Note table B.3 is not used in RFC 3491. */
  val mapping: CodePointMapping,
  /** Prohibited code points. */
  val prohibitSet: CodePointSet,
  /** RandALCat code points; bidi category is "R" or "AL". */
  val randalcatSet: CodePointSet,
  /** LCat code points; bidi category is "L". */
  val lcatSet: CodePointSet,
) {
  /**
   * Returns [input] in canonical form according to these rules, or null if no such canonical form
   * exists for it.
   */
  operator fun invoke(input: String): String? = invoke(Buffer().writeUtf8(input))

  internal operator fun invoke(input: BufferedSource): String? {
    // 1. Map.
    val mapResult = Buffer()
    while (!input.exhausted()) {
      val codePoint = input.readUtf8CodePoint()
      when (val mappedCodePoint = mapping[codePoint]) {
        null -> mapResult.writeUtf8CodePoint(codePoint)
        else -> mapResult.writeUtf8(mappedCodePoint)
      }
    }

    // 2. Normalize
    // TODO.

    // 3 and 4. Check prohibited characters and bidi.
    val validateBuffer = mapResult.copy()
    var firstIsRandalcat = false
    while (!validateBuffer.exhausted()) {
      val first = validateBuffer.size == mapResult.size
      val codePoint = validateBuffer.readUtf8CodePoint()
      if (codePoint in prohibitSet) return null

      if (first) {
        firstIsRandalcat = codePoint in randalcatSet

      } else if (firstIsRandalcat) {
        // 'If a string contains any RandALCat character, the string MUST NOT contain any LCat
        // character.'
        if (codePoint in lcatSet) return null

        // 'If a string contains any RandALCat character, a RandALCat character MUST be the last
        // character of the string.'
        if (validateBuffer.exhausted() && codePoint !in randalcatSet) return null

      } else {
        // 'If a string contains any RandALCat character, a RandALCat character MUST be the first
        // character of the string'
        if (codePoint in randalcatSet) return null
      }
    }

    return mapResult.readUtf8()
  }
}

interface CodePointMapping {
  /**
   * Returns a (possibly-empty) string that [codePoint] maps to, or null if [codePoint] is not
   * mapped.
   */
  operator fun get(codePoint: Int): String?
}

interface CodePointSet {
  operator fun contains(codePoint: Int): Boolean
}
