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
}
