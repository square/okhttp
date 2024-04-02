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

import okio.BufferedSink

/**
 * An IDNA mapping table optimized for small code and data size.
 *
 * Code Points in Sections
 * =======================
 *
 * The full range of code points is 0..0x10fffe. We can represent any of these code points with 21
 * bits.
 *
 * We split each code point into a 14-bit prefix and a 7-bit suffix. All code points with the same
 * prefix are called a 'section'. There are 128 code points per section.
 *
 * Ranges Data (32,612 bytes)
 * ==========================
 *
 * Each entry is 4 bytes, and represents a _range_ of code points that all share a common 14-bit
 * prefix. Entries are sorted by their complete code points.
 *
 * The 4 bytes are named b0, b1, b2 and b3. We also define these supplemental values:
 *
 *  * **b2a**: b2 + 0x80
 *  * **b3a**: b3 + 0x80
 *  * **b2b3**: (b2 << 7) + b3
 *
 * b0
 * --
 *
 * The inclusive start of the range. We get the first 14 bits of this code point from the section
 * and the last 7 bits from this byte.
 *
 * The end of the range is not encoded, but can be inferred by looking at the start of the range
 * that follows.
 *
 * b1
 * --
 *
 * This is either a mapping decision or the length of the mapped output, according to this table:
 *
 * ```
 *  0..63 : Length of the UTF-16 sequence that this range maps to. The offset is b2b3.
 * 64..79 : Offset by a fixed negative offset. The bottom 4 bits of b1 are the top 4 bits of the offset.
 * 80..95 : Offset by a fixed positive offset. The bottom 4 bits of b1 are the top 4 bits of the offset.
 *    119 : Ignored.
 *    120 : Valid.
 *    121 : Disallowed
 *    122 : Mapped inline to the sequence: [b2].
 *    123 : Mapped inline to the sequence: [b2a].
 *    124 : Mapped inline to the sequence: [b2, b3].
 *    125 : Mapped inline to the sequence: [b2a, b3].
 *    126 : Mapped inline to the sequence: [b2, b3a].
 *    127 : Mapped inline to the sequence: [b2a, b3a].
 *
 * The range goes until the beginning of the next range.
 *
 * When b2 and b3 are unused, their values are set to 0x2d ('-').
 *
 * Section Index (1,240 bytes)
 * ===========================
 *
 * Each entry is 4 bytes, and represents all the code points that share a 14-bit prefix. Entries are
 * sorted by this 14-bit prefix.
 *
 * We define these values:
 *
 *  * **b0b1s7**: (b0 << 14) + (b1 << 7)
 *  * **b2b3s2**: (b2 << 9) + (b3 << 2)
 *
 * b0b1s7 is the section prefix. If a section is omitted, that means its ranges data exactly matches
 * that of the preceding section.
 *
 * b2b3s2 is the offset into the ranges data. It is shifted by 2 because ranges are 4-byte aligned.
 *
 * Mappings Data (4,719 bytes)
 * ===========================
 *
 * This is UTF-8 character data. It is indexed into by b2b3 in the ranges dataset.
 *
 * Mappings may overlap.
 *
 * ASCII-Only
 * ==========
 *
 * Neither the section index nor the ranges data use bit 0x80 anywhere. That means the data is
 * strictly ASCII. This is intended to make it efficient to encode this data as a string, and to
 * index into it as a string.
 *
 * The mappings data contains non-ASCII characters.
 */
internal class IdnaMappingTable internal constructor(
  val sections: String,
  val ranges: String,
  val mappings: String,
) {
  /**
   * Returns true if the [codePoint] was applied successfully. Returns false if it was disallowed.
   */
  fun map(
    codePoint: Int,
    sink: BufferedSink,
  ): Boolean {
    val sectionsIndex = findSectionsIndex(codePoint)

    val rangesPosition = sections.read14BitInt(sectionsIndex + 2)

    val rangesLimit =
      when {
        sectionsIndex + 4 < sections.length -> sections.read14BitInt(sectionsIndex + 6)
        else -> ranges.length / 4
      }

    val rangesIndex = findRangesOffset(codePoint, rangesPosition, rangesLimit)

    when (val b1 = ranges[rangesIndex + 1].code) {
      in 0..63 -> {
        // Length of the UTF-16 sequence that this range maps to. The offset is b2b3.
        val beginIndex = ranges.read14BitInt(rangesIndex + 2)
        sink.writeUtf8(mappings, beginIndex, beginIndex + b1)
      }
      in 64..79 -> {
        // Mapped inline as codePoint delta to subtract
        val b2 = ranges[rangesIndex + 2].code
        val b3 = ranges[rangesIndex + 3].code

        val codepointDelta = (b1 and 0xF shl 14) or (b2 shl 7) or b3
        sink.writeUtf8CodePoint(codePoint - codepointDelta)
      }
      in 80..95 -> {
        // Mapped inline as codePoint delta to add
        val b2 = ranges[rangesIndex + 2].code
        val b3 = ranges[rangesIndex + 3].code

        val codepointDelta = (b1 and 0xF shl 14) or (b2 shl 7) or b3
        sink.writeUtf8CodePoint(codePoint + codepointDelta)
      }
      119 -> {
        // Ignored.
      }
      120 -> {
        // Valid.
        sink.writeUtf8CodePoint(codePoint)
      }
      121 -> {
        // Disallowed.
        sink.writeUtf8CodePoint(codePoint)
        return false
      }
      122 -> {
        // Mapped inline to the sequence: [b2].
        sink.writeByte(ranges[rangesIndex + 2].code)
      }
      123 -> {
        // Mapped inline to the sequence: [b2a].
        sink.writeByte(ranges[rangesIndex + 2].code or 0x80)
      }
      124 -> {
        // Mapped inline to the sequence: [b2, b3].
        sink.writeByte(ranges[rangesIndex + 2].code)
        sink.writeByte(ranges[rangesIndex + 3].code)
      }
      125 -> {
        // Mapped inline to the sequence: [b2a, b3].
        sink.writeByte(ranges[rangesIndex + 2].code or 0x80)
        sink.writeByte(ranges[rangesIndex + 3].code)
      }
      126 -> {
        // Mapped inline to the sequence: [b2, b3a].
        sink.writeByte(ranges[rangesIndex + 2].code)
        sink.writeByte(ranges[rangesIndex + 3].code or 0x80)
      }
      127 -> {
        // Mapped inline to the sequence: [b2a, b3a].
        sink.writeByte(ranges[rangesIndex + 2].code or 0x80)
        sink.writeByte(ranges[rangesIndex + 3].code or 0x80)
      }
      else -> error("unexpected rangesIndex for $codePoint")
    }

    return true
  }

  /**
   * Binary search [sections] for [codePoint], looking at its top 14 bits.
   *
   * This binary searches over 4-byte entries, and so it needs to adjust binary search indices
   * in (by dividing by 4) and out (by multiplying by 4).
   */
  private fun findSectionsIndex(codePoint: Int): Int {
    val target = (codePoint and 0x1fff80) shr 7
    val offset =
      binarySearch(
        position = 0,
        limit = sections.length / 4,
      ) { index ->
        val entryIndex = index * 4
        val b0b1 = sections.read14BitInt(entryIndex)
        return@binarySearch target.compareTo(b0b1)
      }

    return when {
      offset >= 0 -> offset * 4 // This section was found by binary search.
      else -> (-offset - 2) * 4 // Not found? Use the preceding element.
    }
  }

  /**
   * Binary search [ranges] for [codePoint], looking at its bottom 7 bits.
   *
   * This binary searches over 4-byte entries, and so it needs to adjust binary search indices
   * in (by dividing by 4) and out (by multiplying by 4).
   */
  private fun findRangesOffset(
    codePoint: Int,
    position: Int,
    limit: Int,
  ): Int {
    val target = codePoint and 0x7f
    val offset =
      binarySearch(
        position = position,
        limit = limit,
      ) { index ->
        val entryIndex = index * 4
        val b0 = ranges[entryIndex].code
        return@binarySearch target.compareTo(b0)
      }

    return when {
      offset >= 0 -> offset * 4 // This entry was found by binary search.
      else -> (-offset - 2) * 4 // Not found? Use the preceding element.
    }
  }
}

internal fun String.read14BitInt(index: Int): Int {
  val b0 = this[index].code
  val b1 = this[index + 1].code
  return (b0 shl 7) + b1
}

/**
 * An extremely generic binary search that doesn't know what data it's searching over. The caller
 * provides indexes and a comparison function, and this calls that function iteratively.
 *
 * @return the index of the match. If no match is found this is `(-1 - insertionPoint)`, where the
 *     inserting the element at `insertionPoint` will retain sorted order.
 */
inline fun binarySearch(
  position: Int,
  limit: Int,
  compare: (Int) -> Int,
): Int {
  // Do the binary searching bit.
  var low = position
  var high = limit - 1
  while (low <= high) {
    val mid = (low + high) / 2
    val compareResult = compare(mid)
    when {
      compareResult < 0 -> high = mid - 1
      compareResult > 0 -> low = mid + 1
      else -> return mid // Match!
    }
  }

  return -low - 1 // insertionPoint is before the first element.
}
