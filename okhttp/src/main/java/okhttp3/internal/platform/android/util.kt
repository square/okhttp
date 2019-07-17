package okhttp3.internal.platform.android

import android.util.Log
import okhttp3.internal.platform.Platform

private const val MAX_LOG_LENGTH = 4000

internal fun androidLog(level: Int, message: String, t: Throwable?) {
  var logMessage = message
  val logLevel = if (level == Platform.WARN) Log.WARN else Log.DEBUG
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