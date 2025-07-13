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
import android.os.Build
import java.lang.IllegalStateException
import okhttp3.internal.platform.android.AndroidLog

actual object PlatformRegistry {
  actual fun findPlatform(): Platform {
    AndroidLog.enable()

    val androidPlatform =
      Android10Platform.buildIfSupported()
        ?: AndroidPlatform.buildIfSupported()
    if (androidPlatform != null) return androidPlatform

    // If the API version is 0, assume this is the Android artifact, but running on the JVM.
    // Robolectric?
    if (Build.VERSION.SDK_INT == 0) {
      return Jdk9Platform.buildIfSupported()
        ?: Platform()
    }

    throw IllegalStateException("Expected Android API level 21+ but was ${Build.VERSION.SDK_INT}")
  }

  actual val isAndroid: Boolean
    get() = true

  var applicationContext: Context?
    get() = (Platform.get() as? ContextAwarePlatform)?.applicationContext
    set(value) {
      (Platform.get() as? ContextAwarePlatform)?.applicationContext = value
    }
}
