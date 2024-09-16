/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http1

import okhttp3.Headers
import okio.BufferedSource

/**
 * Parse all headers delimited by "\r\n" until an empty line. This throws if headers exceed `headerLimit`.
 */
class HeadersReader(
  val source: BufferedSource,
  private var headerLimit: Long = HEADER_LIMIT
) {

  /** Read a single line counted against the header size limit. */
  fun readLine(): String {
    val line = if(headerLimit == HEADER_NO_LIMIT)
        source.readUtf8LineStrict()
      else
        source.readUtf8LineStrict(headerLimit)
    if(headerLimit != HEADER_NO_LIMIT) {
      if(line.length.toLong() > headerLimit) {
        headerLimit = HEADER_NO_LIMIT - 1L
      } else {
        headerLimit -= line.length.toLong()
      }
    }
    return line
  }

  /** Reads headers or trailers. */
  fun readHeaders(): Headers {
    val result = Headers.Builder()
    while (true) {
      val line = readLine()
      if (line.isEmpty()) break
      result.addLenient(line)
    }
    return result.build()
  }

  companion object {
    const val HEADER_LIMIT: Long = 256L * 1024L
    const val HEADER_NO_LIMIT: Long = -1L
  }
}
