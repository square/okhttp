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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Confirm we get the expected table whether we build it from the .txt file or compact that. */
class IdnaMappingTableTest {
  private lateinit var table: SimpleIdnaMappingTable
  private lateinit var compactTable: IdnaMappingTable

  @BeforeEach
  fun setUp() {
    val path = "/okhttp3/internal/idna/IdnaMappingTable.txt".toPath()
    val plainTable =
      FileSystem.RESOURCES.read(path) {
        readPlainTextIdnaMappingTable()
      }
    table = plainTable
    val data = buildIdnaMappingTableData(plainTable)
    compactTable =
      IdnaMappingTable(
        sections = data.sections,
        ranges = data.ranges,
        mappings = data.mappings,
      )
  }

  @Test fun regularMappings() {
    assertThat("hello".map()).isEqualTo("hello")
    assertThat("hello-world".map()).isEqualTo("hello-world")
    assertThat("HELLO".map()).isEqualTo("hello")
    assertThat("Hello".map()).isEqualTo("hello")

    // These compound characters map their its components.
    assertThat("¼".map()).isEqualTo("1⁄4")
    assertThat("™".map()).isEqualTo("tm")
  }

  /** Confirm the compact table satisfies is documented invariants. */
  @Test fun validateCompactTableInvariants() {
    // Less than 16,834 bytes, because we binary search on a 14-bit index.
    assertThat(compactTable.sections.length).isLessThan(1 shl 14)

    // Less than 65,536 bytes, because we binary search on a 14-bit index with a stride of 4 bytes.
    assertThat(compactTable.ranges.length).isLessThan((1 shl 14) * 4)

    // Less than 16,384 chars, because we index on a 14-bit index in the ranges table.
    assertThat(compactTable.mappings.length).isLessThan(1 shl 14)

    // Confirm the data strings are ASCII.
    for (dataString in listOf<String>(compactTable.sections, compactTable.ranges)) {
      for (codePoint in dataString.codePoints()) {
        assertThat(codePoint and 0x7f).isEqualTo(codePoint)
      }
    }

    // Confirm the sections are increasing.
    val rangesIndices = mutableListOf<Int>()
    val rangesOffsets = mutableListOf<Int>()
    for (i in 0 until compactTable.sections.length step 4) {
      rangesIndices += compactTable.sections.read14BitInt(i)
      rangesOffsets += compactTable.sections.read14BitInt(i + 2)
    }
    assertThat(rangesIndices).isEqualTo(rangesIndices.sorted())

    // Check the ranges.
    for (r in 0 until rangesOffsets.size) {
      val rangePos = rangesOffsets[r] * 4
      val rangeLimit =
        when {
          r + 1 < rangesOffsets.size -> rangesOffsets[r + 1] * 4
          else -> rangesOffsets.size * 4
        }

      // Confirm this range starts with byte 0.
      assertThat(compactTable.ranges[rangePos].code).isEqualTo(0)

      // Confirm this range's index byte is increasing.
      val rangeStarts = mutableListOf<Int>()
      for (i in rangePos until rangeLimit step 4) {
        rangeStarts += compactTable.ranges[i].code
      }
      assertThat(rangeStarts).isEqualTo(rangeStarts.sorted())
    }
  }

  @Test fun deviations() {
    assertThat("ß".map()).isEqualTo("ß")
    assertThat("ς".map()).isEqualTo("ς")
    assertThat("\u200c".map()).isEqualTo("\u200c")
    assertThat("\u200d".map()).isEqualTo("\u200d")
  }

  @Test fun ignored() {
    assertThat("\u200b".map()).isEqualTo("")
    assertThat("\ufeff".map()).isEqualTo("")
  }

  @Test fun disallowed() {
    assertThat("\u0080".mapExpectingErrors()).isEqualTo("\u0080")
  }

  @Test fun disallowedStd3Valid() {
    assertThat("_".map()).isEqualTo("_")
    assertThat("/".map()).isEqualTo("/")
    assertThat("≠".map()).isEqualTo("≠")
  }

  @Test fun disallowedStd3Mapped() {
    assertThat("\u00b8".map()).isEqualTo("\u0020\u0327")
    assertThat("⑴".map()).isEqualTo("(1)")
  }

  @Test fun outOfBounds() {
    assertFailsWith<IllegalArgumentException> {
      table.map(-1, Buffer())
    }
    table.map(0, Buffer()) // Lowest legal code point.
    table.map(0x10ffff, Buffer()) // Highest legal code point.
    assertFailsWith<IllegalArgumentException> {
      table.map(0x110000, Buffer())
    }
  }

  @Test fun binarySearchEvenSizedRange() {
    val table = listOf(1, 3, 5, 7, 9, 11)

    // Search for matches.
    assertEquals(0, binarySearch(0, 6) { index -> 1.compareTo(table[index]) })
    assertEquals(1, binarySearch(0, 6) { index -> 3.compareTo(table[index]) })
    assertEquals(2, binarySearch(0, 6) { index -> 5.compareTo(table[index]) })
    assertEquals(3, binarySearch(0, 6) { index -> 7.compareTo(table[index]) })
    assertEquals(4, binarySearch(0, 6) { index -> 9.compareTo(table[index]) })
    assertEquals(5, binarySearch(0, 6) { index -> 11.compareTo(table[index]) })

    // Search for misses.
    assertEquals(-1, binarySearch(0, 6) { index -> 0.compareTo(table[index]) })
    assertEquals(-2, binarySearch(0, 6) { index -> 2.compareTo(table[index]) })
    assertEquals(-3, binarySearch(0, 6) { index -> 4.compareTo(table[index]) })
    assertEquals(-4, binarySearch(0, 6) { index -> 6.compareTo(table[index]) })
    assertEquals(-5, binarySearch(0, 6) { index -> 8.compareTo(table[index]) })
    assertEquals(-6, binarySearch(0, 6) { index -> 10.compareTo(table[index]) })
    assertEquals(-7, binarySearch(0, 6) { index -> 12.compareTo(table[index]) })
  }

  @Test fun binarySearchOddSizedRange() {
    val table = listOf(1, 3, 5, 7, 9)

    // Search for matches.
    assertEquals(0, binarySearch(0, 5) { index -> 1.compareTo(table[index]) })
    assertEquals(1, binarySearch(0, 5) { index -> 3.compareTo(table[index]) })
    assertEquals(2, binarySearch(0, 5) { index -> 5.compareTo(table[index]) })
    assertEquals(3, binarySearch(0, 5) { index -> 7.compareTo(table[index]) })
    assertEquals(4, binarySearch(0, 5) { index -> 9.compareTo(table[index]) })

    // Search for misses.
    assertEquals(-1, binarySearch(0, 5) { index -> 0.compareTo(table[index]) })
    assertEquals(-2, binarySearch(0, 5) { index -> 2.compareTo(table[index]) })
    assertEquals(-3, binarySearch(0, 5) { index -> 4.compareTo(table[index]) })
    assertEquals(-4, binarySearch(0, 5) { index -> 6.compareTo(table[index]) })
    assertEquals(-5, binarySearch(0, 5) { index -> 8.compareTo(table[index]) })
    assertEquals(-6, binarySearch(0, 5) { index -> 10.compareTo(table[index]) })
  }

  @Test fun binarySearchSingleElementRange() {
    val table = listOf(1)

    // Search for matches.
    assertEquals(0, binarySearch(0, 1) { index -> 1.compareTo(table[index]) })

    // Search for misses.
    assertEquals(-1, binarySearch(0, 1) { index -> 0.compareTo(table[index]) })
    assertEquals(-2, binarySearch(0, 1) { index -> 2.compareTo(table[index]) })
  }

  @Test fun binarySearchEmptyRange() {
    assertEquals(-1, binarySearch(0, 0) { error("unexpected call") })
  }

  /** Confirm the compact table has the exact same behavior as the plain table. */
  @Test fun comparePlainAndCompactTables() {
    val buffer = Buffer()
    for (codePoint in 0..0x10ffff) {
      val allowedByTable = table.map(codePoint, buffer)
      val tableMappedTo = buffer.readUtf8()

      val allowedByCompactTable = compactTable.map(codePoint, buffer)
      val compactTableMappedTo = buffer.readUtf8()

      assertThat(allowedByCompactTable).isEqualTo(allowedByTable)
      assertThat(compactTableMappedTo).isEqualTo(tableMappedTo)
    }
  }

  /** Confirm we didn't corrupt any data in code generation. */
  @Test fun compareConstructedAndGeneratedCompactTables() {
    assertThat(IDNA_MAPPING_TABLE.sections).isEqualTo(compactTable.sections)
    assertThat(IDNA_MAPPING_TABLE.ranges).isEqualTo(compactTable.ranges)
    assertThat(IDNA_MAPPING_TABLE.mappings).isEqualTo(compactTable.mappings)
  }

  private fun String.map(): String {
    val buffer = Buffer()
    for (codePoint in codePoints()) {
      require(table.map(codePoint, buffer))
    }
    return buffer.readUtf8()
  }

  private fun String.mapExpectingErrors(): String {
    val buffer = Buffer()
    var errorCount = 0
    for (codePoint in codePoints()) {
      if (!table.map(codePoint, buffer)) errorCount++
    }
    assertThat(errorCount).isGreaterThan(0)
    return buffer.readUtf8()
  }
}
