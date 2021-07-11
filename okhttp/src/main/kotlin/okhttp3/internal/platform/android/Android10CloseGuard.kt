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

import okhttp3.internal.platform.CloseGuard

class Android10CloseGuard : CloseGuard {
  override fun createAndOpen(closer: String): Any? = android.util.CloseGuard().apply { open(closer) }

  override fun warnIfOpen(closeGuardInstance: Any?): Boolean {
    val instance = closeGuardInstance as? android.util.CloseGuard
      ?: throw IllegalArgumentException("Invalid closeguard $closeGuardInstance")

    instance.warnIfOpen()
  }
}