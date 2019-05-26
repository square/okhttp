/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.logging

import okio.Buffer
import java.io.EOFException

/**
 * Returns true if the body in question probably contains human readable text. Uses a small
 * sample of code points to detect unicode control characters commonly used in binary file
 * signatures.
 */
internal fun Buffer.isProbablyUtf8(): Boolean {
  try {
    val prefix = Buffer()
    val byteCount = size.coerceAtMost(64)
    copyTo(prefix, 0, byteCount)
    for (i in 0 until 16) {
      if (prefix.exhausted()) {
        break
      }
      val codePoint = prefix.readUtf8CodePoint()
      if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
        return false
      }
    }
    return true
  } catch (_: EOFException) {
    return false // Truncated UTF-8 sequence.
  }
}
