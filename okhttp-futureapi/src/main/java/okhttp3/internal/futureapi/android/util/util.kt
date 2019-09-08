package okhttp3.internal.futureapi.android.util

import android.util.Log

object LogX {
  const val WARN: Int = Log.WARN
  const val DEBUG: Int = Log.DEBUG

  fun println(priority: Int, tag: String, msg: String) {
    Log.println(priority, tag, msg)
  }

  fun getStackTraceString(t: Throwable): String = Log.getStackTraceString(t)
}

val isAndroid: Boolean = try {
  // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
  Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl")
  true
} catch (_: ClassNotFoundException) {
  false
}
