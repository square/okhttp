/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp.android.test

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.matchesPredicate
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Remote")
class EchTest {

  @Test
  fun testHttpsRequest() {
    val client: OkHttpClient =
      OkHttpClient
        .Builder()
        .build()

    val cloudflareEchBody =
      client.sendRequest(Request.Builder().url("https://cloudflare-ech.com/").build()) {
        it.body.string()
      }
    assertThat(cloudflareEchBody).matchesPredicate { it.contains("ECH enabled") }

    val cloudflareBody = client.sendRequest(
      Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()
    ) {
      it.body.string()
    }
    assertThat(cloudflareBody).matchesPredicate { it.contains("ECH enabled") }

    val tlsEchBody = client.sendRequest(Request.Builder().url("https://tls-ech.dev/").build()) {
      it.body.string()
    }
    assertThat(tlsEchBody).matchesPredicate { it.contains("ECH enabled") }
  }

  private fun <T> OkHttpClient.sendRequest(request: Request, fn: (Response) -> T): T {
    val response = newCall(request).execute()

    return response.use {
      fn(it)
    }
  }
}
