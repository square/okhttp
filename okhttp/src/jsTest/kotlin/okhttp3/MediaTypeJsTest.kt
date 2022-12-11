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

import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.MediaType.Companion.toMediaType

class MediaTypeJsTest {
  fun MediaType.charsetName(): String? = parameter("charset")

  @Test
  fun testIllegalCharsetName() {
    val mediaType = "text/plain; charset=\"!@#$%^&*()\"".toMediaType()
    assertEquals("!@#\$%^&*()", mediaType.charsetName())
  }

  @Test fun testUnsupportedCharset() {
    val mediaType = "text/plain; charset=us-wtf".toMediaType()
    assertEquals("us-wtf", mediaType.charsetName())
  }

  @Test fun testCharsetNameIsDoubleQuotedAndSingleQuoted() {
    val mediaType = "text/plain;charset=\"'utf-8'\"".toMediaType()
    assertEquals("'utf-8'", mediaType.charsetName())
  }

  @Test fun testCharsetNameIsDoubleQuotedSingleQuote() {
    val mediaType = "text/plain;charset=\"'\"".toMediaType()
    assertEquals("'", mediaType.charsetName())
  }
}
