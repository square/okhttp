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

import android.util.Log
import okhttp3.internal.platform.Platform

private const val MAX_LOG_LENGTH = 4000

internal fun androidLog(level: Int, message: String, t: Throwable?) {
  var logMessage = message
  val logLevel = when (level) {
    Platform.WARN -> Log.WARN
    else -> Log.DEBUG
  }
  if (t != null) logMessage = logMessage + '\n'.toString() + Log.getStackTraceString(t)

  // Split by line, then ensure each line can fit into Log's maximum length.
  var i = 0
  val length = logMessage.length
  while (i < length) {
    var newline = logMessage.indexOf('\n', i)
    newline = if (newline != -1) newline else length
    do {
      val end = minOf(newline, i + MAX_LOG_LENGTH)
      Log.println(logLevel, "OkHttp", logMessage.substring(i, end))
      i = end
    } while (i < newline)
    i++
  }
}