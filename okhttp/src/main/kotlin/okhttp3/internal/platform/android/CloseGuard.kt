/*
 * Copyright (C) 2021 Square, Inc.
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

package okhttp3.internal.platform.android

import android.os.Build
import okhttp3.internal.platform.Platform

/**
 * Provides access to the Android CloseGuard. Android uses this in
 * combination with android.os.StrictMode to report on leaked java.io.Closeable's.
 */
interface CloseGuard {
  fun createAndOpen(closer: String): Any?
  fun warnIfOpen(closeGuardInstance: Any?): Boolean

  companion object {
    val Noop = object : CloseGuard {
      override fun createAndOpen(closer: String): Any? = null
      override fun warnIfOpen(closeGuardInstance: Any?) = false
    }

    fun get(): CloseGuard = when {
      !Platform.isAndroid -> throw IllegalStateException("CloseGuard is Android only")
      Build.VERSION.SDK_INT >= 30 -> Android10CloseGuard()
      else -> DalvikCloseGuard.get()
    }
  }
}