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
import okhttp3.internal.platform.PlatformRegistry

/**
 * Configure the ApplicationContext. Not needed unless the AndroidX Startup Initialiser is disabled, or running
 * a robolectric test.
 *
 * The functionality that will fail with a valid Context is primarily Cookies and URL Domain handling.
 */
fun OkHttp.initialiseApplicationContext(context: Context) {
  PlatformRegistry.applicationContext = context.applicationContext
}
