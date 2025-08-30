/*
 * Copyright (c) 2025 Block, Inc.
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
 *
 */
package okhttp.android.test

import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttp
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [23, 26, 30, 33, 35],
)
class RobolectricOkHttpClientTest : BaseOkHttpClientUnitTest() {
  @Before
  fun setContext() {
    // This is awkward because Robolectric won't run our initializers and we don't want test deps
    // https://github.com/robolectric/robolectric/issues/8461
    OkHttp.initialize(ApplicationProvider.getApplicationContext())
  }
}
