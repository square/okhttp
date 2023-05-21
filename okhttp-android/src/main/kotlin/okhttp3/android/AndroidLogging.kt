package okhttp3.android

import android.util.Log
import okhttp3.logging.LoggingEventListener

fun LoggingEventListener.Companion.AndroidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp"
) = LoggingEventListener.Factory { Log.println(priority, tag, it) }
