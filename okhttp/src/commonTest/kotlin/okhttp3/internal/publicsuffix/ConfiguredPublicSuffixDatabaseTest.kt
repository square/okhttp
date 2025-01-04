/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3.internal.publicsuffix

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import okio.Buffer
import org.junit.jupiter.api.Test

class ConfiguredPublicSuffixDatabaseTest {
  private val list = ConfiguredPublicSuffixList()
  private val publicSuffixDatabase = PublicSuffixDatabase(list)

  @Test fun longestMatchWins() {
    list.bytes =
      Buffer()
        .writeUtf8("com\n")
        .writeUtf8("my.square.com\n")
        .writeUtf8("square.com\n").readByteString()

    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com"))
      .isEqualTo("example.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.example.com"))
      .isEqualTo("example.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.bar.square.com"))
      .isEqualTo("bar.square.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
  }

  @Test fun wildcardMatch() {
    list.bytes =
      Buffer()
        .writeUtf8("*.square.com\n")
        .writeUtf8("com\n")
        .writeUtf8("example.com\n")
        .readByteString()

    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.square.com")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("bar.foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
  }

  @Test fun boundarySearches() {
    list.bytes =
      Buffer()
        .writeUtf8("bbb\n")
        .writeUtf8("ddd\n")
        .writeUtf8("fff\n")
        .readByteString()

    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("aaa")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ggg")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ccc")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("eee")).isNull()
  }

  @Test fun exceptionRule() {
    list.bytes =
      Buffer()
        .writeUtf8("*.jp\n")
        .writeUtf8("*.square.jp\n")
        .writeUtf8("example.com\n")
        .writeUtf8("square.com\n")
        .readByteString()
    list.exceptionBytes =
      Buffer()
        .writeUtf8("my.square.jp\n")
        .readByteString()

    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.square.jp"))
      .isEqualTo("my.square.jp")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.jp"))
      .isEqualTo("my.square.jp")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my1.square.jp")).isNull()
  }

  @Test fun noEffectiveTldPlusOne() {
    list.bytes =
      Buffer()
        .writeUtf8("*.jp\n")
        .writeUtf8("*.square.jp\n")
        .writeUtf8("example.com\n")
        .writeUtf8("square.com\n")
        .readByteString()
    list.exceptionBytes =
      Buffer()
        .writeUtf8("my.square.jp\n")
        .readByteString()

    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.square.jp")).isNull()
  }
}
