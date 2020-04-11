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
package okhttp3.internal.platform.android

import java.lang.reflect.Method

/**
 * Provides access to the internal dalvik.system.CloseGuard class. Android uses this in
 * combination with android.os.StrictMode to report on leaked java.io.Closeable's. Available since
 * Android API 11.
 */
internal class CloseGuard(
  private val getMethod: Method?,
  private val openMethod: Method?,
  private val warnIfOpenMethod: Method?
) {

  fun createAndOpen(closer: String): Any? {
    if (getMethod != null) {
      try {
        val closeGuardInstance = getMethod.invoke(null)
        openMethod!!.invoke(closeGuardInstance, closer)
        return closeGuardInstance
      } catch (_: Exception) {
      }
    }
    return null
  }

  fun warnIfOpen(closeGuardInstance: Any?): Boolean {
    var reported = false
    if (closeGuardInstance != null) {
      try {
        warnIfOpenMethod!!.invoke(closeGuardInstance)
        reported = true
      } catch (_: Exception) {
      }
    }
    return reported
  }

  companion object {
    fun get(): CloseGuard {
      var getMethod: Method?
      var openMethod: Method?
      var warnIfOpenMethod: Method?

      try {
        val closeGuardClass = Class.forName("dalvik.system.CloseGuard")
        getMethod = closeGuardClass.getMethod("get")
        openMethod = closeGuardClass.getMethod("open", String::class.java)
        warnIfOpenMethod = closeGuardClass.getMethod("warnIfOpen")
      } catch (_: Exception) {
        getMethod = null
        openMethod = null
        warnIfOpenMethod = null
      }

      return CloseGuard(getMethod, openMethod, warnIfOpenMethod)
    }
  }
}
