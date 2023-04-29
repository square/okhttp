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
import assertk.assertions.containsExactly
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test

class MappingTablesTest {
  @Test fun simplifyCombinesMultipleMappings() {
    assertThat(
      mergeAdjacentRanges(
        listOf(
          Mapping(0x0232, 0x0232, TYPE_MAPPED, "a".encodeUtf8()),
          Mapping(0x0233, 0x0233, TYPE_VALID, ByteString.EMPTY),
          Mapping(0x0234, 0x0236, TYPE_VALID, ByteString.EMPTY),
          Mapping(0x0237, 0x0239, TYPE_VALID, ByteString.EMPTY),
          Mapping(0x023a, 0x023a, TYPE_MAPPED, "b".encodeUtf8()),
        )
      )
    ).containsExactly(
      Mapping(0x0232, 0x0232, TYPE_MAPPED, "a".encodeUtf8()),
      Mapping(0x0233, 0x0239, TYPE_VALID, ByteString.EMPTY),
      Mapping(0x023a, 0x023a, TYPE_MAPPED, "b".encodeUtf8()),
    )
  }

  @Test fun simplifyDoesNotCombineWhenMappedTargetsAreDifferent() {
    assertThat(
      mergeAdjacentRanges(
        listOf(
          Mapping(0x0041, 0x0041, TYPE_MAPPED, "a".encodeUtf8()),
          Mapping(0x0042, 0x0042, TYPE_MAPPED, "b".encodeUtf8()),
        )
      )
    ).containsExactly(
      Mapping(0x0041, 0x0041, TYPE_MAPPED, "a".encodeUtf8()),
      Mapping(0x0042, 0x0042, TYPE_MAPPED, "b".encodeUtf8()),
    )
  }

  @Test fun simplifyCanonicalizesType() {
    assertThat(
      mergeAdjacentRanges(
        listOf(
          Mapping(0x0000, 0x002c, TYPE_DISALLOWED_STD3_VALID, ByteString.EMPTY),
        )
      )
    ).containsExactly(
      Mapping(0x0000, 0x002c, TYPE_VALID, ByteString.EMPTY),
    )
  }

  @Test fun simplifyCombinesCanonicalEquivalent() {
    assertThat(
      mergeAdjacentRanges(
        listOf(
          Mapping(0x0000, 0x002c, TYPE_DISALLOWED_STD3_VALID, ByteString.EMPTY),
          Mapping(0x002d, 0x002e, TYPE_VALID, ByteString.EMPTY)
        )
      )
    ).containsExactly(
      Mapping(0x0000, 0x002e, TYPE_VALID, ByteString.EMPTY),
    )
  }

  @Test fun withSectionStartsSplits() {
    assertThat(
      withoutSectionSpans(
        listOf(
          Mapping(0x40000, 0x40180, TYPE_DISALLOWED, ByteString.EMPTY),
        )
      )
    ).containsExactly(
      Mapping(0x40000, 0x4007f, TYPE_DISALLOWED, ByteString.EMPTY),
      Mapping(0x40080, 0x400ff, TYPE_DISALLOWED, ByteString.EMPTY),
      Mapping(0x40100, 0x4017f, TYPE_DISALLOWED, ByteString.EMPTY),
      Mapping(0x40180, 0x40180, TYPE_DISALLOWED, ByteString.EMPTY),
    )
  }

  @Test fun withSectionStartAlreadySplit() {
    assertThat(
      withoutSectionSpans(
        listOf(
          Mapping(0x40000, 0x4007f, TYPE_DISALLOWED, ByteString.EMPTY),
          Mapping(0x40080, 0x400ff, TYPE_DISALLOWED, ByteString.EMPTY),
        )
      )
    ).containsExactly(
      Mapping(0x40000, 0x4007f, TYPE_DISALLOWED, ByteString.EMPTY),
      Mapping(0x40080, 0x400ff, TYPE_DISALLOWED, ByteString.EMPTY),
    )
  }
}
