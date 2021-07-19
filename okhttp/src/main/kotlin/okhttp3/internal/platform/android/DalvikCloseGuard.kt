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
 * combination with android.os.StrictMode to report on leaked java.io.Closeable's.
 */
internal class DalvikCloseGuard(
  private val getMethod: Method,
  private val openMethod: Method,
  private val warnIfOpenMethod: Method
): CloseGuard {

  override fun createAndOpen(closer: String): Any? {
    return try {
        val closeGuardInstance = getMethod.invoke(null)
        openMethod.invoke(closeGuardInstance, closer)
      closeGuardInstance
    } catch (_: Exception) {
      null
    }
  }

  override fun warnIfOpen(closeGuardInstance: Any?): Boolean {
    if (closeGuardInstance != null) {
      try {
        warnIfOpenMethod.invoke(closeGuardInstance)
        return true
      } catch (_: Exception) {
      }
    }

    return false
  }

  companion object {
    fun get(): CloseGuard {
      return try {
        val closeGuardClass = Class.forName("dalvik.system.CloseGuard")
        val getMethod = closeGuardClass.getMethod("get")
        val openMethod = closeGuardClass.getMethod("open", String::class.java)
        val warnIfOpenMethod = closeGuardClass.getMethod("warnIfOpen")

        DalvikCloseGuard(getMethod, openMethod, warnIfOpenMethod)
      } catch (_: Exception) {
        CloseGuard.Noop
      }
    }
  }
}
