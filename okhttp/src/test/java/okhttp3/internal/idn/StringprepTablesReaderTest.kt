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

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.assertj.core.api.Assertions.assertThat

class StringprepTablesReaderTest {
  @Test fun readRfc3491FromResources() {
    val reader = StringprepTablesReader(FileSystem.RESOURCES)
    val nameprep = reader.readNameprep("/okhttp3/internal/idn".toPath())
    assertThat((nameprep.unassigned as RangeListCodePointSet).ranges).hasSize(396)
    assertThat((nameprep.mapping as MappingListCodePointMapping).mappings).hasSize(1398)
    assertThat((nameprep.prohibitSet as RangeListCodePointSet).ranges).hasSize(78)
    assertThat((nameprep.randalcatSet as RangeListCodePointSet).ranges).hasSize(34)
    assertThat((nameprep.lcatSet as RangeListCodePointSet).ranges).hasSize(360)
  }

  @Test fun readCodePointSet() {
    val buffer = Buffer()
    buffer.writeUtf8(
      """
      |  0221
      |  0234-024F
      |  0000-001F; [CONTROL CHARACTERS]
      |  007F; DELETE
      |""".trimMargin()
    )

    val rangeList = buffer.readCodePointSet()
    assertEquals(
      listOf(
        0x0221..0x0221,
        0x0234..0x024f,
        0x0000..0x001f, // [CONTROL CHARACTERS]
        0x007f..0x007f, // DELETE
      ),
      rangeList.ranges
    )
  }

  @Test fun readCodePointMapping() {
    val buffer = Buffer()
    buffer.writeUtf8(
      """
      |    180C; ; Map to nothing
      |    0041; 0061; Case map
      |    0390; 03B9 0308 0301; Case map
      |""".trimMargin()
    )

    val mappings = buffer.readCodePointMappings()
    assertEquals(
      mapOf(
        0x180c to "", // Map to nothing
        'A'.code to "a", // Case map
        'ΐ'.code to "\u03B9\u0308\u0301" // Case map
      ),
      mappings.mappings
    )
  }
}
