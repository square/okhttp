/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.platform

import android.content.Context
import androidx.startup.Initializer

/**
 * Androidx Startup initializer to ensure that the AndroidPlatform has access to the application context.
 */
class PlatformInitializer : Initializer<Platform> {
  override fun create(context: Context): Platform {
    PlatformRegistry.applicationContext = context

    return Platform.get()
  }

  override fun dependencies(): List<Class<Initializer<*>>> = listOf()
}
