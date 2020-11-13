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
package okhttp.regression;

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLHandshakeException

/**
 * Let's Encrypt expiring root test.
 *
 * Read https://community.letsencrypt.org/t/mobile-client-workarounds-for-isrg-issue/137807
 * for background.
 */
@RunWith(AndroidJUnit4::class)
class LetsEncryptTest {
  private var client = OkHttpClient()

  @Test fun getFailsWithoutAdditionalCert() {
    val androidMorEarlier = Build.VERSION.SDK_INT <= 23
    try {
      val request = Request.Builder()
        .url("https://valid-isrgrootx1.letsencrypt.org/robots.txt")
        .build()
      client.newCall(request).execute().use { response ->
        assertEquals(404, response.code())
        assertEquals(Protocol.HTTP_2, response.protocol())
      }
      if (androidMorEarlier) {
        fail()
      }
    } catch (sslhe: SSLHandshakeException) {
      assertTrue(androidMorEarlier)
    }
  }
}
