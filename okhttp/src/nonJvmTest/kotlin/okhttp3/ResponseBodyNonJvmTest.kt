/*
 * Copyright (C) 2016 Square, Inc.
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
import kotlin.test.fail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException

class ResponseBodyNonJvmTest {
  @Test
  fun unicodeTextWithUnsupportedEncoding() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody("text/plain; charset=EBCDIC".toMediaType())
    try {
      assertThat(body.string()).isEqualTo(text)
      fail()
    } catch (ioe: IOException) {
      assertThat(ioe.message).isEqualTo("Unsupported encoding 'EBCDIC'")
    }
  }
}
