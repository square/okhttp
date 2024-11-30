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
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package okhttp3.android

import android.content.Context
import androidx.startup.Initializer
import okhttp3.internal.platform.AndroidPlatform
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.AndroidContextPlatform

/**
 * An [Initializer] that initializes the OkHttp [Platform] instance.
 *
 * This initializer sets the Android context for the platform if it's an [AndroidPlatform].
 * This allows OkHttp to access Android-specific features like the application's cache directory.
 *
 * This initializer has no dependencies.
 */
class OkHttpStartupInitializer : Initializer<Platform> {
  override fun create(context: Context): Platform {
    val platform = Platform.get()

    if (platform is AndroidContextPlatform) {
      platform.setAndroidContext(context)
    }

    return platform
  }

  override fun dependencies(): List<Class<out Initializer<*>>> {
    return emptyList()
  }
}
