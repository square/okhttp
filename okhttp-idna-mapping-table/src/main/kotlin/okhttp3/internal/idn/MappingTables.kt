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

import kotlin.math.abs
import kotlin.streams.toList
import okio.Buffer

/** Index [table] for compactness as specified by `IdnaMappingTable`. */
fun buildIdnaMappingTableData(table: SimpleIdnaMappingTable): IdnaMappingTableData {
  val simplified = mergeAdjacentRanges(table.mappings)
  val withoutSectionSpans = withoutSectionSpans(simplified)
  val sections = sections(withoutSectionSpans)

  val rangesBuffer = Buffer()
  val mappingsBuffer = StringBuilder()
  val sectionIndexBuffer = Buffer()

  var previousMappedRanges: List<MappedRange>? = null

  for ((section, sectionMappedRanges) in sections) {
    // Skip sequential ranges when they are equal.
    if (sectionMappedRanges == previousMappedRanges) continue
    previousMappedRanges = sectionMappedRanges

    val sectionOffset = rangesBuffer.size.toInt() / 4

    // Section prefix.
    sectionIndexBuffer.writeByte(section and 0x1fc000 shr 14)
    sectionIndexBuffer.writeByte((section and 0x3f80) shr 7)

    // Section index.
    sectionIndexBuffer.writeByte((sectionOffset and 0x3f80) shr 7)
    sectionIndexBuffer.writeByte(sectionOffset and 0x7f)

    // Ranges.
    for (range in sectionMappedRanges) {
      rangesBuffer.writeByte(range.rangeStart)

      when (range) {
        is MappedRange.Constant -> {
          rangesBuffer.writeByte(range.b1)
          rangesBuffer.writeByte('-'.code)
          rangesBuffer.writeByte('-'.code)
        }
        is MappedRange.Inline1 -> {
          rangesBuffer.writeByte(range.b1)
          rangesBuffer.writeByte(range.b2)
          rangesBuffer.writeByte('-'.code)
        }
        is MappedRange.Inline2 -> {
          rangesBuffer.writeByte(range.b1)
          rangesBuffer.writeByte(range.b2)
          rangesBuffer.writeByte(range.b3)
        }
        is MappedRange.InlineDelta -> {
          rangesBuffer.writeByte(range.b1)
          rangesBuffer.writeByte(range.b2)
          rangesBuffer.writeByte(range.b3)
        }
        is MappedRange.External -> {
          // Write the mapping.
          val mappingOffset: Int
          val mappedTo = range.mappedTo.utf8()
          val mappingIndex = mappingsBuffer.indexOf(mappedTo)
          if (mappingIndex == -1) {
            mappingOffset = mappingsBuffer.length
            mappingsBuffer.append(mappedTo)
          } else {
            mappingOffset = mappingIndex
          }

          // Write the range bytes.
          val b1 = mappedTo.length
          val b2 = (mappingOffset and 0x3f80) shr 7
          val b3 = mappingOffset and 0x7f
          rangesBuffer.writeByte(b1)
          rangesBuffer.writeByte(b2)
          rangesBuffer.writeByte(b3)
        }
      }
    }
  }

  return IdnaMappingTableData(
    sections = sectionIndexBuffer.readUtf8(),
    ranges = rangesBuffer.readUtf8(),
    mappings = mappingsBuffer.toString(),
  )
}

/**
 * If [mapping] qualifies to be encoded as [MappedRange.InlineDelta] return new instance, otherwise null.
 * An [MappedRange.InlineDelta] must be a mapping from a single code-point to a single code-point with a difference
 * that can be represented in 2^18-1.
 */
internal fun inlineDeltaOrNull(mapping: Mapping): MappedRange.InlineDelta? {
  if (mapping.hasSingleSourceCodePoint) {
    val sourceCodePoint = mapping.sourceCodePoint0
    val mappedCodePoints = mapping.mappedTo.utf8().codePoints().toList()
    if (mappedCodePoints.size == 1) {
      val codePointDelta = mappedCodePoints.single() - sourceCodePoint
      if (MappedRange.InlineDelta.MAX_VALUE >= abs(codePointDelta)) {
        return MappedRange.InlineDelta(mapping.rangeStart, codePointDelta)
      }
    }
  }
  return null
}

/**
 * Inputs must have applied [withoutSectionSpans].
 */
internal fun sections(mappings: List<Mapping>): Map<Int, List<MappedRange>> {
  val result = mutableMapOf<Int, MutableList<MappedRange>>()

  for (mapping in mappings) {
    require(!mapping.spansSections)

    val section = mapping.section
    val rangeStart = mapping.rangeStart

    val sectionList = result.getOrPut(section) { mutableListOf() }

    sectionList +=
      when (mapping.type) {
        TYPE_MAPPED ->
          run {
            val deltaMapping = inlineDeltaOrNull(mapping)
            if (deltaMapping != null) {
              return@run deltaMapping
            }

            when (mapping.mappedTo.size) {
              1 -> MappedRange.Inline1(rangeStart, mapping.mappedTo)
              2 -> MappedRange.Inline2(rangeStart, mapping.mappedTo)
              else -> MappedRange.External(rangeStart, mapping.mappedTo)
            }
          }

        TYPE_IGNORED, TYPE_VALID, TYPE_DISALLOWED -> {
          MappedRange.Constant(rangeStart, mapping.type)
        }

        else -> error("unexpected mapping type: ${mapping.type}")
      }
  }

  for (sectionList in result.values) {
    mergeAdjacentDeltaMappedRanges(sectionList)
  }

  return result.toMap()
}

/**
 * Modifies [ranges] to combine any adjacent [MappedRange.InlineDelta] of same size to single entry.
 * @returns same instance of [ranges] for convenience
 */
internal fun mergeAdjacentDeltaMappedRanges(ranges: MutableList<MappedRange>): MutableList<MappedRange> {
  var i = 0
  while (i < ranges.size) {
    val curr = ranges[i]
    if (curr is MappedRange.InlineDelta) {
      val j = i + 1
      mergeAdjacent@ while (j < ranges.size) {
        val next = ranges[j]
        if (next is MappedRange.InlineDelta &&
          curr.codepointDelta == next.codepointDelta
        ) {
          ranges.removeAt(j)
        } else {
          break@mergeAdjacent
        }
      }
    }
    i++
  }
  return ranges
}

/**
 * Returns a copy of [mappings], splitting to ensure that each mapping is entirely contained within
 * a single section.
 */
internal fun withoutSectionSpans(mappings: List<Mapping>): List<Mapping> {
  val result = mutableListOf<Mapping>()

  val i = mappings.iterator()
  var current = i.next()

  while (true) {
    if (current.spansSections) {
      result +=
        Mapping(
          current.sourceCodePoint0,
          current.section + 0x7f,
          current.type,
          current.mappedTo,
        )
      current =
        Mapping(
          current.section + 0x80,
          current.sourceCodePoint1,
          current.type,
          current.mappedTo,
        )
    } else {
      result += current
      current = if (i.hasNext()) i.next() else break
    }
  }

  return result
}

/** Returns a copy of [mappings] with adjacent ranges merged wherever possible. */
internal fun mergeAdjacentRanges(mappings: List<Mapping>): List<Mapping> {
  var index = 0
  val result = mutableListOf<Mapping>()

  while (index < mappings.size) {
    val mapping = mappings[index]
    val type = canonicalizeType(mapping.type)
    val mappedTo = mapping.mappedTo

    var unionWith: Mapping = mapping
    index++

    while (index < mappings.size) {
      val next = mappings[index]

      if (type != canonicalizeType(next.type)) break
      if (type == TYPE_MAPPED && mappedTo != next.mappedTo) break

      unionWith = next
      index++
    }

    result +=
      Mapping(
        sourceCodePoint0 = mapping.sourceCodePoint0,
        sourceCodePoint1 = unionWith.sourceCodePoint1,
        type = type,
        mappedTo = mappedTo,
      )
  }

  return result
}

internal fun canonicalizeType(type: Int): Int {
  return when (type) {
    TYPE_IGNORED -> TYPE_IGNORED

    TYPE_MAPPED,
    TYPE_DISALLOWED_STD3_MAPPED,
    -> TYPE_MAPPED

    TYPE_DEVIATION,
    TYPE_DISALLOWED_STD3_VALID,
    TYPE_VALID,
    -> TYPE_VALID

    TYPE_DISALLOWED -> TYPE_DISALLOWED

    else -> error("unexpected type: $type")
  }
}

internal infix fun Byte.and(mask: Int): Int = toInt() and mask

internal infix fun Short.and(mask: Int): Int = toInt() and mask

internal infix fun Int.and(mask: Long): Long = toLong() and mask
