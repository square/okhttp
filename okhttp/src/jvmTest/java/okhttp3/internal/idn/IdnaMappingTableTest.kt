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
import kotlin.test.assertFailsWith
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IdnaMappingTableTest {
  private lateinit var table: IdnaMappingTable

  @BeforeEach
  fun setUp() {
    table = FileSystem.RESOURCES.read("/okhttp3/internal/idna/IdnaMappingTable.txt".toPath()) {
      readPlainTextIdnaMappingTable()
    }
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

  @Test fun deviations() {
    assertThat("ß".map()).isEqualTo("ss")
    assertThat("ς".map()).isEqualTo("σ")
    assertThat("\u200c".map()).isEqualTo("")
    assertThat("\u200d".map()).isEqualTo("")
  }

  @Test fun ignored() {
    assertThat("\u200b".map()).isEqualTo("")
    assertThat("\ufeff".map()).isEqualTo("")
  }

  @Test fun disallowed() {
    assertThat("\u0080".mapExpectingErrors()).isEqualTo("")
  }

  @Test fun disallowedStd3Valid() {
    assertThat("/".map()).isEqualTo("/")
  }

  @Test fun disallowedStd3Mapped() {
    assertThat("\u00b8".map()).isEqualTo("\u0020\u0327")
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

  private fun String.map(): String {
    val result = Buffer()
    for (codePoint in codePoints()) {
      require(table.map(codePoint, result))
    }
    return result.readUtf8()
  }

  private fun String.mapExpectingErrors(): String {
    val result = Buffer()
    var errorCount = 0
    for (codePoint in codePoints()) {
      if (!table.map(codePoint, result)) errorCount++
    }
    assertThat(errorCount).isGreaterThan(0)
    return result.readUtf8()
  }
}
