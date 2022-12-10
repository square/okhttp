/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.android.test.sni;

import android.util.Log
import java.security.cert.X509Certificate
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Test for overriding SNI.
 */
@Tag("Remote")
class SniOverrideTest {
  @Test
  fun getWithDns() {
    val client = client.newBuilder()
      .dns {
        Dns.SYSTEM.lookup("sni.cloudflaressl.com")
      }
      .build()

    val request = Request.Builder()
      .url("https://cloudflare-dns.com/cdn-cgi/trace")
      .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(response.body.string()).contains("h=cloudflare-dns.com")
    }
  }
}
