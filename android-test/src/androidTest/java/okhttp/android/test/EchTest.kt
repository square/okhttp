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

import android.net.ssl.EchConfigList
import android.net.ssl.SSLSockets
import java.net.Socket
import javax.net.ssl.SSLSocket
import okhttp3.DelegatingSSLSocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.platform.AndroidDnsResolverDns
import okhttp3.internal.platform.Platform
import okio.IOException
import org.junit.jupiter.api.Test
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.SVCBBase

class EchTest {

  @Test
  fun testHttpsRequest() {
    val client: OkHttpClient =
      OkHttpClient
        .Builder()
        .build()

    client.sendRequest(Request.Builder().url("https://cloudflare-ech.com/").build()) {
      println(it.body.string())
    }

    client.sendRequest(
      Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()
    ) {
      println(it.body.string())
    }

    client.sendRequest(Request.Builder().url("https://tls-ech.dev/").build()) {
      println(it.body.string())
    }
  }

  private fun OkHttpClient.sendRequest(request: Request, fn: (Response) -> Unit = {}) {
    try {
      val response = newCall(request).execute()

      response.use {
        fn(it)
      }
    } catch (ioe: IOException) {
      ioe.printStackTrace()
    }
  }
}
