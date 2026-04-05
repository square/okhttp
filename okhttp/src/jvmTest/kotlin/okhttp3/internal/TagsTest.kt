/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test

class TagsTest {
  @Test
  fun emptyTags() {
    val tags = EmptyTags
    assertThat(tags[String::class]).isNull()
  }

  @Test
  fun singleElement() {
    val tags = EmptyTags.plus(String::class, "hello")
    assertThat(tags[String::class]).isEqualTo("hello")
  }

  @Test
  fun multipleElements() {
    val tags =
      EmptyTags
        .plus(String::class, "hello")
        .plus(Integer::class, 5 as Integer)
    assertThat(tags[String::class]).isEqualTo("hello")
    assertThat(tags[Integer::class]).isEqualTo(5)
  }

  /** The implementation retains no nodes from the original linked list. */
  @Test
  fun replaceFirstElement() {
    val tags =
      EmptyTags
        .plus(String::class, "a")
        .plus(Integer::class, 5 as Integer)
        .plus(Boolean::class, true)
        .plus(String::class, "b")
    assertThat(tags[String::class]).isEqualTo("b")
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true, class kotlin.String=b}")
  }

  /** The implementation retains only the first node from the original linked list. */
  @Test
  fun replaceMiddleElement() {
    val tags =
      EmptyTags
        .plus(Integer::class, 5 as Integer)
        .plus(String::class, "a")
        .plus(Boolean::class, true)
        .plus(String::class, "b")
    assertThat(tags[String::class]).isEqualTo("b")
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true, class kotlin.String=b}")
  }

  /** The implementation retains all but the first node from the original linked list. */
  @Test
  fun replaceLastElement() {
    val tags =
      EmptyTags
        .plus(Integer::class, 5 as Integer)
        .plus(Boolean::class, true)
        .plus(String::class, "a")
        .plus(String::class, "b")
    assertThat(tags[String::class]).isEqualTo("b")
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true, class kotlin.String=b}")
  }

  /** The implementation retains no nodes from the original linked list. */
  @Test
  fun removeFirstElement() {
    val tags =
      EmptyTags
        .plus(String::class, "a")
        .plus(Integer::class, 5 as Integer)
        .plus(Boolean::class, true)
        .plus(String::class, null)
    assertThat(tags[String::class]).isNull()
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true}")
  }

  /** The implementation retains only the first node from the original linked list. */
  @Test
  fun removeMiddleElement() {
    val tags =
      EmptyTags
        .plus(Integer::class, 5 as Integer)
        .plus(String::class, "a")
        .plus(Boolean::class, true)
        .plus(String::class, null)
    assertThat(tags[String::class]).isNull()
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true}")
  }

  /** The implementation retains all but the first node from the original linked list. */
  @Test
  fun removeLastElement() {
    val tags =
      EmptyTags
        .plus(Integer::class, 5 as Integer)
        .plus(Boolean::class, true)
        .plus(String::class, "a")
        .plus(String::class, null)
    assertThat(tags[String::class]).isNull()
    assertThat(tags.toString())
      .isEqualTo("{class kotlin.Int=5, class kotlin.Boolean=true}")
  }

  @Test
  fun removeUntilEmpty() {
    val tags =
      EmptyTags
        .plus(Integer::class, 5 as Integer)
        .plus(Boolean::class, true)
        .plus(String::class, "a")
        .plus(String::class, null)
        .plus(Integer::class, null)
        .plus(Boolean::class, null)
    assertThat(tags).isEqualTo(EmptyTags)
    assertThat(tags.toString()).isEqualTo("{}")
  }

  @Test
  fun removeAbsentFromEmpty() {
    val tags = EmptyTags.plus(String::class, null)
    assertThat(tags).isEqualTo(EmptyTags)
    assertThat(tags.toString()).isEqualTo("{}")
  }

  @Test
  fun removeAbsentFromNonEmpty() {
    val tags =
      EmptyTags
        .plus(String::class, "a")
        .plus(Integer::class, null)
    assertThat(tags[String::class]).isEqualTo("a")
    assertThat(tags.toString()).isEqualTo("{class kotlin.String=a}")
  }

  @Test
  fun computeIfAbsentWhenEmpty() {
    val tags = EmptyTags
    val atomicTags = AtomicReference<Tags>(tags)
    assertThat(atomicTags.computeIfAbsent(String::class) { "a" }).isEqualTo("a")
    assertThat(atomicTags.get()[String::class]).isEqualTo("a")
  }

  @Test
  fun computeIfAbsentWhenPresent() {
    val tags = EmptyTags.plus(String::class, "a")
    val atomicTags = AtomicReference(tags)
    assertThat(atomicTags.computeIfAbsent(String::class) { "b" }).isEqualTo("a")
    assertThat(atomicTags.get()[String::class]).isEqualTo("a")
  }

  @Test
  fun computeIfAbsentWhenDifferentKeyRaceLostDuringCompute() {
    val tags = EmptyTags
    val atomicTags = AtomicReference<Tags>(tags)
    val result =
      atomicTags.computeIfAbsent(String::class) {
        // 'Race' by making another computeIfAbsent call. In practice this would be another thread.
        assertThat(atomicTags.computeIfAbsent(Integer::class) { 5 as Integer }).isEqualTo(5)
        "a"
      }
    assertThat(result).isEqualTo("a")
    assertThat(atomicTags.get()[String::class]).isEqualTo("a")
    assertThat(atomicTags.get()[Integer::class]).isEqualTo(5)
  }

  @Test
  fun computeIfAbsentWhenSameKeyRaceLostDuringCompute() {
    val tags = EmptyTags
    val atomicTags = AtomicReference<Tags>(tags)
    val result =
      atomicTags.computeIfAbsent(String::class) {
        // 'Race' by making another computeIfAbsent call. In practice this would be another thread.
        assertThat(atomicTags.computeIfAbsent(String::class) { "b" }).isEqualTo("b")
        "a"
      }
    assertThat(result).isEqualTo("b")
    assertThat(atomicTags.get()[String::class]).isEqualTo("b")
  }

  @Test
  fun computeIfAbsentOnlyComputesOnceAfterRaceLost() {
    var computeCount = 0
    val tags = EmptyTags
    val atomicTags = AtomicReference<Tags>(tags)
    val result =
      atomicTags.computeIfAbsent(String::class) {
        computeCount++
        // 'Race' by making another computeIfAbsent call. In practice this would be another thread.
        assertThat(atomicTags.computeIfAbsent(Integer::class) { 5 as Integer }).isEqualTo(5)
        "a"
      }
    assertThat(result).isEqualTo("a")
    assertThat(computeCount).isEqualTo(1)
    assertThat(atomicTags.get()[Integer::class]).isEqualTo(5)
    assertThat(atomicTags.get()[String::class]).isEqualTo("a")
  }
}
