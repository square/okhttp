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
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import okio.Path.Companion.toPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class StringprepTest {
  private val reader = StringprepTablesReader(FileSystem.RESOURCES)
  private val rfc3491 = reader.readNameprep("/okhttp3/internal/idn".toPath())
  private val stringPrep = Stringprep(
    unassigned = RangeListCodePointSet(listOf()),
    mapping = rfc3491.mapping,
    prohibitSet = rfc3491.prohibitSet,
    randalcatSet = rfc3491.randalcatSet,
    lcatSet = rfc3491.lcatSet,
  )

  @Test fun mappedToNothing() {
    assertThat(stringPrep("a\u00ADz")).isEqualTo("az")
    assertThat(stringPrep("a\u200Dz")).isEqualTo("az")
  }

  @Test fun caseFolding() {
    assertThat(stringPrep("localhost")).isEqualTo("localhost")
    assertThat(stringPrep("Localhost")).isEqualTo("localhost")
    assertThat(stringPrep("LOCALHOST")).isEqualTo("localhost")
    assertThat(stringPrep("abc123def")).isEqualTo("abc123def")
    assertThat(stringPrep("ß")).isEqualTo("ss")
    assertThat(stringPrep("İ")).isEqualTo("i̇")
    assertThat(stringPrep("ϒ")).isEqualTo("υ")
    assertThat(stringPrep("ὒ")).isEqualTo("ὒ")
  }

  @Test fun otherFolding() {
    assertThat(stringPrep("℉")).isEqualTo("°f")
    assertThat(stringPrep("㍱")).isEqualTo("hpa")
  }

  @Test fun prohibitionCharactersSpaces() {
    assertThat(stringPrep(" ")).isEqualTo(" ") // ASCII space not prohibited.
    assertThat(stringPrep("\u2003")).isNull() // EM SPACE
  }

  @Test fun prohibitionControlCharacters() {
    assertThat(stringPrep("\u007f")).isEqualTo("\u007f") // ASCII delete not prohibited.
    assertThat(stringPrep("\u0080")).isNull()
    assertThat(stringPrep("\u2029")).isNull()
    assertThat(stringPrep("\ud834\udd7a")).isNull() // Note that this is one code point.
  }

  @Test fun prohibitionPrivateUse() {
    assertThat(stringPrep("\uf8ff")).isNull()
    assertThat(stringPrep("\udbff\udffd")).isNull() // Note that this is one code point.
  }

  @Test fun prohibitionNonCharacter() {
    assertThat(stringPrep("\ufdd0")).isNull()
    assertThat(stringPrep("\ufffe")).isNull()
    assertThat(stringPrep("\udbff\udffe")).isNull()
  }

  /**
   * Because our API always transcodes through UTF-8, and that transcoding replaces unpaired
   * surrogates with '?', we can't test this behavior with Java Strings. Instead, pass the surrogate
   * code itself encoded as UTF-8 sequence.
   */
  @Test fun prohibitionSurrogateCodes() {
    // UTF-8 encoding of the high surrogate U+D800.
    assertThat(stringPrep(Buffer().write("eda080".decodeHex()))).isNull()
  }

  @Test fun prohibitionInappropriateForPlainText() {
    assertThat(stringPrep("\ufff9")).isNull()
    assertThat(stringPrep("\ufffc")).isNull()
  }

  @Test fun prohibitionIdeographic() {
    assertThat(stringPrep("\u2ff0")).isNull()
    assertThat(stringPrep("\u2ffb")).isNull()
  }

  @Test fun prohibitionChangeDisplayPropertiesOrDeprecated() {
    assertThat(stringPrep("\u0340")).isNull() // COMBINING GRAVE TONE MARK
    assertThat(stringPrep("\u200e")).isNull() // LEFT-TO-RIGHT MARK
    assertThat(stringPrep("\u206e")).isNull() // NATIONAL DIGIT SHAPES
    assertThat(stringPrep("\u206f")).isNull() // NOMINAL DIGIT SHAPES
  }

  @Test fun prohibitionTaggingCharacters() {
    assertThat(stringPrep("\udb40\udc01")).isNull() // Note that this is one code point.
    assertThat(stringPrep("\udb40\udc7f")).isNull() // Note that this is one code point.
  }

  /**
   * For this test:
   *
   *  * U+0627 and U+0628 are RandALCat characters
   *  * 'a', 'b', and 'c' are LCat characters.
   *  * '1', '2', and '3' are neither.
   */
  @Test fun bidiRules() {
    assertThat(stringPrep("\u0627")).isEqualTo("\u0627")
    assertThat(stringPrep("\u0627\u0628")).isEqualTo("\u0627\u0628")
    assertThat(stringPrep("\u0627123\u0628")).isEqualTo("\u0627123\u0628")

    // LCat embedded
    assertThat(stringPrep("\u0627abc\u0628")).isNull()

    // RandALCat not last.
    assertThat(stringPrep("\u0627123")).isNull()

    // RandALCat not first.
    assertThat(stringPrep("123\u0628")).isNull()
  }
}
