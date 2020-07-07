/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.tls.internal.der

import okio.ByteString

/**
 * A value whose type is not specified statically. Use this with [Adapters.any] which will attempt
 * to resolve a concrete type.
 */
internal data class AnyValue(
  var tagClass: Int,
  var tag: Long,
  var constructed: Boolean = false,
  var length: Long = -1L,
  val bytes: ByteString
) {
  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + tagClass
    result = 31 * result + tag.toInt()
    result = 31 * result + (if (constructed) 0 else 1)
    result = 31 * result + length.toInt()
    result = 31 * result + bytes.hashCode()
    return result
  }
}
