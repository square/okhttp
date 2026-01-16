/*
 * Copyright (C) 2025 Block, Inc.
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

import java.net.InetAddress
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test

class EchTest {

  private var client: OkHttpClient =
    OkHttpClient
      .Builder()
      .dns {
        if (it == "crypto.cloudflare.com") {
          println("returning hints")
          listOf(InetAddress.getByName("162.159.135.79"), InetAddress.getByName("162.159.136.79"))
        } else {
          Dns.SYSTEM.lookup(it)
        }
      }
      .build()

  @Test
  fun testHttpsRequest() {
    sendRequest(Request.Builder().url("https://cloudflare-ech.com/").build()) {
    }

    sendRequest(Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()) {
      println(it.body.string())
    }
  }

  private fun sendRequest(request: Request, fn: (Response) -> Unit = {}) {
    val response = client.newCall(request).execute()

    response.use {
      fn(it)
    }
  }
}
