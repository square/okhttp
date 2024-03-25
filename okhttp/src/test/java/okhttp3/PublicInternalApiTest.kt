/*
 * Copyright (C) 2019 Square, Inc.
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

import okhttp3.internal.http.HttpMethod.permitsRequestBody
import okhttp3.internal.http.HttpMethod.requiresRequestBody
import okhttp3.internal.http.hasBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION_ERROR")
class PublicInternalApiTest {
  @Test
  fun permitsRequestBody() {
    assertTrue(permitsRequestBody("POST"))
    assertFalse(permitsRequestBody("HEAD"))
  }

  @Test
  fun requiresRequestBody() {
    assertTrue(requiresRequestBody("PUT"))
    assertFalse(requiresRequestBody("GET"))
  }

  @Test
  fun hasBody() {
    val request = Request.Builder().url("http://example.com").build()
    val response =
      Response.Builder().code(200)
        .message("OK")
        .request(request)
        .protocol(Protocol.HTTP_2)
        .build()
    assertTrue(hasBody(response))
  }
}
