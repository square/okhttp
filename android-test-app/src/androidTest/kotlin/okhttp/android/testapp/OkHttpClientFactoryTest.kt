/*
 * Copyright (C) 2023 Block, Inc.
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
package okhttp.android.testapp

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.android.OkHttpClientContext.okHttpClient
import org.junit.Test

/**
 * Run with "./gradlew :android-test-app:connectedCheck -PandroidBuild=true" and make sure ANDROID_SDK_ROOT is set.
 */
class OkHttpClientFactoryTest {
  @Test
  fun testUsesCorrectFactory() {
    val application = ApplicationProvider.getApplicationContext<TestApplication>()

    val client = application.okHttpClient
    assertThat(client.cache?.maxSize()).isEqualTo(5_000_000)
    assertThat(client.cache?.directory?.name).isEqualTo("test-app-cache")
  }
}
