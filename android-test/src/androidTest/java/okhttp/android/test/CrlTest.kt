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
package okhttp.android.test

import java.net.URL
import java.util.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.logging.LoggingEventListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
class CrlTest() {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension public val clientTestRule = OkHttpClientTestRule().apply {
    logger = Logger.getLogger(CrlTest::class.java.name)
  }

  private var client: OkHttpClient = clientTestRule.newClientBuilder()
    .eventListenerFactory(LoggingEventListener.Factory(logger = { println(it) }))
    .build()

  @Test
  fun testRequest() {
    URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(client))

    val request = Request.Builder().url("https://icloud.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(404, response.code)
    }
  }
}
