/*
 * Copyright (C) 2013 Square, Inc.
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
import okhttp3.internal.commonParameter
import okhttp3.internal.commonToMediaType
import okhttp3.internal.commonToMediaTypeOrNull
import okhttp3.internal.commonToString

actual class MediaType internal actual constructor(
  internal actual val mediaType: String,

  actual val type: String,

  actual val subtype: String,

  internal actual val parameterNamesAndValues: Array<String>
) {
  actual fun parameter(name: String): String? = commonParameter(name)

  actual override fun toString(): String = commonToString()

  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  actual companion object {
    actual fun String.toMediaType(): MediaType = commonToMediaType()

    actual fun String.toMediaTypeOrNull(): MediaType? = commonToMediaTypeOrNull()
  }
}
