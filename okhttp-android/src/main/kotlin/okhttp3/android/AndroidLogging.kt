package okhttp3.android

import android.util.Log
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener

fun LoggingEventListener.Companion.AndroidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp"
) = LoggingEventListener.Factory { Log.println(priority, tag, it) }


fun HttpLoggingInterceptor.Companion.AndroidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp"
) = HttpLoggingInterceptor { Log.println(priority, tag, it) }
