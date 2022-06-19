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

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import okhttp3.RequestBody.Companion.toRequestBody

class CommonRequestBodyTest {
  @Test
  fun correctContentType() {
    val body = "Body"
    val requestBody = body.toRequestBody(MediaType("text/plain", "text", "plain", arrayOf()))

    val contentType = requestBody.contentType()!!

    assertThat(contentType.mediaType).isEqualTo("text/plain; charset=utf-8")
    assertThat(contentType.parameter("charset")).isEqualTo("utf-8")
  }
}
