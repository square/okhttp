package okhttp3.android

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.OkHttpClient


object OkHttpClientContext {
  private var _okHttpClient: OkHttpClient? = null

  val Context.okHttpClient: OkHttpClient
    get() {
      return _okHttpClient ?: newOkHttpClient(this)
    }

  @Synchronized
  private fun newOkHttpClient(context: Context): OkHttpClient {
    _okHttpClient?.let { return it }

    val newOkHttpClient = (context.applicationContext as? OkHttpClientFactory)?.newOkHttpClient()
      ?: OkHttpClient.newAndroidClient(context = context, debug = context.isDebug)
    _okHttpClient = newOkHttpClient
    return newOkHttpClient
  }

  private val Context.isDebug: Boolean
    get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
