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

import java.nio.charset.Charset
import okhttp3.internal.commonEquals
import okhttp3.internal.commonHashCode
import okhttp3.internal.commonParameter
import okhttp3.internal.commonToMediaType
import okhttp3.internal.commonToMediaTypeOrNull
import okhttp3.internal.commonToString

actual class MediaType internal actual constructor(
  internal actual val mediaType: String,

  @get:JvmName("type") actual val type: String,

  @get:JvmName("subtype") actual val subtype: String,

  internal actual val parameterNamesAndValues: Array<String>
) {

  /**
   * Returns the charset of this media type, or [defaultValue] if either this media type doesn't
   * specify a charset, or if its charset is unsupported by the current runtime.
   */
  @JvmOverloads
  fun charset(defaultValue: Charset? = null): Charset? {
    val charset = parameter("charset") ?: return defaultValue
    return try {
      Charset.forName(charset)
    } catch (_: IllegalArgumentException) {
      defaultValue // This charset is invalid or unsupported. Give up.
    }
  }

  actual fun parameter(name: String): String? = commonParameter(name)

  @JvmName("-deprecated_type")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "type"),
    level = DeprecationLevel.ERROR
  )
  fun type(): String = type

  @JvmName("-deprecated_subtype")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "subtype"),
    level = DeprecationLevel.ERROR
  )
  fun subtype(): String = subtype

  actual override fun toString(): String = commonToString()

  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  actual companion object {
    @JvmStatic
    @JvmName("get")
    actual fun String.toMediaType(): MediaType = commonToMediaType()

    @JvmStatic
    @JvmName("parse")
    actual fun String.toMediaTypeOrNull(): MediaType? = commonToMediaTypeOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "mediaType.toMediaType()",
        imports = ["okhttp3.MediaType.Companion.toMediaType"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun get(mediaType: String): MediaType = mediaType.toMediaType()

    @JvmName("-deprecated_parse")
    @Deprecated(
      message = "moved to extension function",
      replaceWith = ReplaceWith(
        expression = "mediaType.toMediaTypeOrNull()",
        imports = ["okhttp3.MediaType.Companion.toMediaTypeOrNull"]
      ),
      level = DeprecationLevel.ERROR
    )
    fun parse(mediaType: String): MediaType? = mediaType.toMediaTypeOrNull()
  }
}
