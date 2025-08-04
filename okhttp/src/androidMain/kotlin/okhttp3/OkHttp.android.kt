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
package okhttp3

import android.content.Context
import okhttp3.internal.CONST_VERSION
import okhttp3.internal.platform.PlatformRegistry

actual object OkHttp {
  @JvmField
  actual val VERSION: String = CONST_VERSION

  /**
   * Configure the ApplicationContext. Not needed unless the AndroidX Startup [Initializer] is disabled, or running
   * a robolectric test.
   *
   * The functionality that will fail without a valid Context is primarily Cookies and URL Domain handling, but
   * may expand in the future.
   */
  fun initialize(applicationContext: Context) {
    if (PlatformRegistry.applicationContext == null) {
      // Make sure we aren't using an Activity or Service Context
      PlatformRegistry.applicationContext = applicationContext.applicationContext
    }
  }
}
