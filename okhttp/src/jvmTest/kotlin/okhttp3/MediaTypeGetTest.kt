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
package okhttp3

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.MediaType.Companion.toMediaType

open class MediaTypeGetTest : MediaTypeTest() {
  override fun parse(string: String): MediaType = string.toMediaType()

  override fun assertInvalid(
    string: String,
    exceptionMessage: String?,
  ) {
    val e =
      assertFailsWith<IllegalArgumentException> {
        parse(string)
      }
    assertEquals(exceptionMessage, e.message)
  }
}
