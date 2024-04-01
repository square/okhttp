/*
 * Copyright (C) 2023 Square, Inc.
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

import java.io.IOException
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Options

/**
 * A decoded [mapping table] that can perform the [mapping step] of IDNA processing.
 *
 * This implementation is optimized for readability over efficiency.
 *
 * This implements non-transitional processing by preserving deviation characters.
 *
 * This implementation's STD3 rules are configured to `UseSTD3ASCIIRules=false`. This is
 * permissive and permits the `_` character.
 *
 * [mapping table]: https://www.unicode.org/reports/tr46/#IDNA_Mapping_Table
 * [mapping step]: https://www.unicode.org/reports/tr46/#ProcessingStepMap
 */
class SimpleIdnaMappingTable internal constructor(
  internal val mappings: List<Mapping>,
) {
  /**
   * Returns true if the [codePoint] was applied successfully. Returns false if it was disallowed.
   */
  fun map(
    codePoint: Int,
    sink: BufferedSink,
  ): Boolean {
    val index =
      mappings.binarySearch {
        when {
          it.sourceCodePoint1 < codePoint -> -1
          it.sourceCodePoint0 > codePoint -> 1
          else -> 0
        }
      }

    // Code points must be in 0..0x10ffff.
    require(index in mappings.indices) { "unexpected code point: $codePoint" }

    val mapping = mappings[index]
    var result = true

    when (mapping.type) {
      TYPE_IGNORED -> Unit
      TYPE_MAPPED, TYPE_DISALLOWED_STD3_MAPPED -> {
        sink.write(mapping.mappedTo)
      }

      TYPE_DEVIATION, TYPE_DISALLOWED_STD3_VALID, TYPE_VALID -> {
        sink.writeUtf8CodePoint(codePoint)
      }

      TYPE_DISALLOWED -> {
        sink.writeUtf8CodePoint(codePoint)
        result = false
      }
    }

    return result
  }
}

private val optionsDelimiter =
  Options.of(
    // 0.
    ".".encodeUtf8(),
    // 1.
    " ".encodeUtf8(),
    // 2.
    ";".encodeUtf8(),
    // 3.
    "#".encodeUtf8(),
    // 4.
    "\n".encodeUtf8(),
  )

private val optionsDot =
  Options.of(
    // 0.
    ".".encodeUtf8(),
  )

private const val DELIMITER_DOT = 0
private const val DELIMITER_SPACE = 1
private const val DELIMITER_SEMICOLON = 2
private const val DELIMITER_HASH = 3
private const val DELIMITER_NEWLINE = 4

private val optionsType =
  Options.of(
    // 0.
    "deviation ".encodeUtf8(),
    // 1.
    "disallowed ".encodeUtf8(),
    // 2.
    "disallowed_STD3_mapped ".encodeUtf8(),
    // 3.
    "disallowed_STD3_valid ".encodeUtf8(),
    // 4.
    "ignored ".encodeUtf8(),
    // 5.
    "mapped ".encodeUtf8(),
    // 6.
    "valid ".encodeUtf8(),
  )

internal const val TYPE_DEVIATION = 0
internal const val TYPE_DISALLOWED = 1
internal const val TYPE_DISALLOWED_STD3_MAPPED = 2
internal const val TYPE_DISALLOWED_STD3_VALID = 3
internal const val TYPE_IGNORED = 4
internal const val TYPE_MAPPED = 5
internal const val TYPE_VALID = 6

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

/**
 * Reads lines from `IdnaMappingTable.txt`.
 *
 * Comment lines are either blank or start with a `#` character. Lines may also end with a comment.
 * All comments are ignored.
 *
 * Regular lines contain fields separated by semicolons.
 *
 * The first element on each line is a single hex code point (like 0041) or a hex code point range
 * (like 0030..0039).
 *
 * The second element on each line is a mapping type, like `valid` or `mapped`.
 *
 * For lines that contain a mapping target, the next thing is a sequence of hex code points (like
 * 0031 2044 0034).
 *
 * All other data is ignored.
 */
fun BufferedSource.readPlainTextIdnaMappingTable(): SimpleIdnaMappingTable {
  val mappedTo = Buffer()
  val result = mutableListOf<Mapping>()

  while (!exhausted()) {
    // Skip comment and empty lines.
    when (select(optionsDelimiter)) {
      DELIMITER_HASH -> {
        skipRestOfLine()
        continue
      }

      DELIMITER_NEWLINE -> {
        continue
      }

      DELIMITER_DOT, DELIMITER_SPACE, DELIMITER_SEMICOLON -> {
        throw IOException("unexpected delimiter")
      }
    }

    // "002F" or "0000..002C"
    val sourceCodePoint0 = readHexadecimalUnsignedLong()
    val sourceCodePoint1 =
      when (select(optionsDot)) {
        DELIMITER_DOT -> {
          if (readByte() != '.'.code.toByte()) throw IOException("expected '..'")
          readHexadecimalUnsignedLong()
        }

        else -> sourceCodePoint0
      }

    skipWhitespace()
    if (readByte() != ';'.code.toByte()) throw IOException("expected ';'")

    // "valid" or "mapped"
    skipWhitespace()
    val type = select(optionsType)

    when (type) {
      TYPE_DEVIATION, TYPE_MAPPED, TYPE_DISALLOWED_STD3_MAPPED -> {
        skipWhitespace()
        if (readByte() != ';'.code.toByte()) throw IOException("expected ';'")

        // Like "0061" or "0031 2044 0034".
        while (true) {
          skipWhitespace()

          when (select(optionsDelimiter)) {
            DELIMITER_HASH -> {
              break
            }

            DELIMITER_DOT, DELIMITER_SEMICOLON, DELIMITER_NEWLINE -> {
              throw IOException("unexpected delimiter")
            }
          }

          mappedTo.writeUtf8CodePoint(readHexadecimalUnsignedLong().toInt())
        }
      }

      TYPE_DISALLOWED, TYPE_DISALLOWED_STD3_VALID, TYPE_IGNORED, TYPE_VALID -> Unit

      else -> throw IOException("unexpected type")
    }

    skipRestOfLine()

    result +=
      Mapping(
        sourceCodePoint0.toInt(),
        sourceCodePoint1.toInt(),
        type,
        mappedTo.readByteString(),
      )
  }

  return SimpleIdnaMappingTable(result)
}

internal data class Mapping(
  val sourceCodePoint0: Int,
  val sourceCodePoint1: Int,
  val type: Int,
  val mappedTo: ByteString,
) {
  val section: Int
    get() = sourceCodePoint0 and 0x1fff80

  val rangeStart: Int
    get() = sourceCodePoint0 and 0x7f

  val hasSingleSourceCodePoint: Boolean
    get() = sourceCodePoint0 == sourceCodePoint1

  val spansSections: Boolean
    get() = (sourceCodePoint0 and 0x1fff80) != (sourceCodePoint1 and 0x1fff80)
}
