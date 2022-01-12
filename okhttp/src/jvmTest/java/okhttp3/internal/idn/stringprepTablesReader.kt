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
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Options
import okio.Path
import okio.buffer
import okio.use

class StringprepTablesReader(
  private val fileSystem: FileSystem,
) {
  /**
   * Returns a [Stringprep] that uses the tables of Nameprep ([RFC 3491]).
   *
   * [RFC 3491]: https://datatracker.ietf.org/doc/html/rfc3491
   */
  fun readNameprep(base: Path): Stringprep {
    val unassigned = readCodePointSet(base / "rfc3454.A.1.txt")
    val mapping = MappingListCodePointMapping(
      mutableMapOf<Int, String>()
        .apply {
          putAll(readCodePointMapping(base / "rfc3454.B.1.txt").mappings)
          putAll(readCodePointMapping(base / "rfc3454.B.2.txt").mappings)
        }
    )
    val prohibitSet = RangeListCodePointSet(
      ranges = mutableListOf<IntRange>()
        .apply {
          addAll(readCodePointSet(base / "rfc3454.C.1.2.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.2.2.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.3.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.4.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.5.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.6.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.7.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.8.txt").ranges)
          addAll(readCodePointSet(base / "rfc3454.C.9.txt").ranges)
        }
    )
    val randalcatSet = readCodePointSet(base / "rfc3454.D.1.txt")
    val lcatSet = readCodePointSet(base / "rfc3454.D.2.txt")
    return Stringprep(
      unassigned = unassigned,
      mapping = mapping,
      prohibitSet = prohibitSet,
      randalcatSet = randalcatSet,
      lcatSet = lcatSet
    )
  }

  /**
   * Reads a set of range lines like the following:
   *
   * ```
   *  0221
   *  0234-024F
   *  0000-001F; [CONTROL CHARACTERS]
   *  007F; DELETE
   * ```
   */
  fun readCodePointSet(path: Path): RangeListCodePointSet {
    fileSystem.source(path).buffer().use { source ->
      return source.readCodePointSet()
    }
  }

  /**
   * Reads a set of mapping lines like the following:
   *
   * ```
   *    180C; ; Map to nothing
   *    0041; 0061; Case map
   *    0390; 03B9 0308 0301; Case map
   * ```
   *
   * Each line maps from a single hexadecimal code point to zero or more hexadecimal code points.
   * Elements are delimited by semicolons with a comment at the end of the line.
   */
  fun readCodePointMapping(path: Path): MappingListCodePointMapping {
    fileSystem.source(path).buffer().use { source ->
      return source.readCodePointMappings()
    }
  }
}

private val optionsSemicolon = Options.of(
  ";".encodeUtf8(), // 0 is ';'.
)

private val optionsSemicolonOrNewlineOrDash = Options.of(
  ";".encodeUtf8(),  // 0 is ';'.
  "\n".encodeUtf8(), // 1 is '\n'.
  "-".encodeUtf8(),  // 2 is '-'.
)

internal fun BufferedSource.readCodePointSet(): RangeListCodePointSet {
  val result = mutableListOf<IntRange>()
  while (!exhausted()) {
    skipWhitespace()
    val startCodePoint = readHexadecimalUnsignedLong().toInt()
    skipWhitespace()
    val intRange = when (select(optionsSemicolonOrNewlineOrDash)) {
      0 -> {
        // ;
        skipRestOfLine()
        IntRange(startCodePoint, startCodePoint)
      }
      1 -> {
        // '\n'
        IntRange(startCodePoint, startCodePoint)
      }
      2 -> {
        // '-'
        val endCodePoint = readHexadecimalUnsignedLong().toInt()
        skipRestOfLine()
        IntRange(startCodePoint, endCodePoint)
      }
      else -> {
        throw IOException("expected ';'")
      }
    }
    result += intRange
  }
  return RangeListCodePointSet(result)
}

internal fun BufferedSource.readCodePointMappings(): MappingListCodePointMapping {
  val result = mutableMapOf<Int, String>()
  val target = Buffer()
  while (!exhausted()) {
    skipWhitespace()
    val sourceCodePoint = readHexadecimalUnsignedLong().toInt()
    skipWhitespace()
    if (select(optionsSemicolon) != 0) throw IOException("expected ';'")
    skipWhitespace()
    while (select(optionsSemicolon) == -1) {
      val targetCodePoint = readHexadecimalUnsignedLong().toInt()
      target.writeUtf8CodePoint(targetCodePoint)
      skipWhitespace()
    }
    skipRestOfLine()
    result[sourceCodePoint] = target.readUtf8()
  }
  return MappingListCodePointMapping(result)
}

private fun BufferedSource.skipWhitespace() {
  while (!exhausted()) {
    if (buffer[0] != ' '.code.toByte()) return
    skip(1L)
  }
}

private fun BufferedSource.skipRestOfLine() {
  when (val newline = indexOf('\n'.code.toByte())) {
    -1L -> skip(buffer.size) // Exhaust this source.
    else -> skip(newline + 1)
  }
}

class MappingListCodePointMapping(
  val mappings: Map<Int, String>
) : CodePointMapping {
  override fun get(codePoint: Int) = mappings[codePoint]
}

class RangeListCodePointSet(
  val ranges: List<IntRange>,
): CodePointSet {
  override fun contains(codePoint: Int) = ranges.any { codePoint in it }
}
