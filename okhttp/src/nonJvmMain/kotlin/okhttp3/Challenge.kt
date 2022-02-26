/*
 * Copyright (C) 2014 Square, Inc.
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

import okhttp3.internal.commonEquals
import okhttp3.internal.commonHashCode
import okhttp3.internal.commonToString

actual class Challenge actual constructor(
  actual val scheme: String,

  authParams: Map<String?, String>
) {
  actual val authParams: Map<String?, String>

  actual val realm: String?
    get() = authParams["realm"]

  actual constructor(scheme: String, realm: String) : this(scheme, mapOf("realm" to realm))

  init {
    val newAuthParams = mutableMapOf<String?, String>()
    for ((key, value) in authParams) {
      val newKey = key?.lowercase()
      newAuthParams[newKey] = value
    }
    this.authParams = newAuthParams.toMap()
  }

  actual override fun equals(other: Any?): Boolean = commonEquals(other)

  actual override fun hashCode(): Int = commonHashCode()

  actual override fun toString(): String = commonToString()
}
