package okhttp3.internal.platform.android

import android.util.Log
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Logger
import okhttp3.internal.platform.Logger.Level
import okhttp3.internal.platform.Logger.Level.DEBUG
import okhttp3.internal.platform.Logger.Level.INFO
import okhttp3.internal.platform.Logger.Level.WARN

private const val MAX_LOG_LENGTH = 4000

@SuppressSignatureCheck
internal fun androidLog(logLevel: Int, message: String, t: Throwable?) {
  var logMessage = message
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

private val Level.toAndroid: Int
  get() = when (this) {
    DEBUG -> Log.DEBUG
    INFO -> Log.INFO
    WARN -> Log.WARN
  }

@SuppressSignatureCheck
class AndroidLogger(val name: String) : Logger {
  override fun debug(message: String, e: Throwable?) {
    androidLog(DEBUG.toAndroid, message, e)
  }

  override fun info(message: String, e: Throwable?) {
    androidLog(INFO.toAndroid, message, e)
  }

  override fun warn(message: String, e: Throwable?) {
    androidLog(WARN.toAndroid, message, e)
  }

  override fun isLoggable(level: Level): Boolean {
    return Log.isLoggable(name, level.toAndroid)
  }
}
