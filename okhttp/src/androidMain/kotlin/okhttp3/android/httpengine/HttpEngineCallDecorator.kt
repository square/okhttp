package okhttp3.android.httpengine

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.cache.CacheInterceptor
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.RetryAndFollowUpInterceptor

class HttpEngineCallDecorator(
  internal val httpEngine: HttpEngine,
  private val useHttpEngine: (Request) -> Boolean = { isHttpEngineSupported() },
) : Call.Decorator {
  // TODO make this work with forked clients
  internal lateinit var client: OkHttpClient

  @SuppressLint("NewApi")
  private val httpEngineInterceptor = HttpEngineInterceptor(this)

  override fun newCall(chain: Call.Chain): Call {
    val call = httpEngineCall(chain)

    return call ?: chain.proceed(chain.request)
  }

  @SuppressLint("NewApi")
  @Synchronized
  private fun httpEngineCall(chain: Call.Chain): Call? {
    if (!useHttpEngine(chain.request)) {
      return null
    }

    if (!::client.isInitialized) {
      val originalClient = chain.client
      client =
        originalClient
          .newBuilder()
          .apply {
            networkInterceptors.clear()

            // TODO refactor RetryAndFollowUpInterceptor to not require the Client directly
            interceptors += RetryAndFollowUpInterceptor(originalClient)
            interceptors += BridgeInterceptor(originalClient.cookieJar)
            interceptors += CacheInterceptor(originalClient.cache)
            interceptors += httpEngineInterceptor
            interceptors +=
              Interceptor {
                throw IllegalStateException("Shouldn't attempt to connect with OkHttp")
              }

            // Keep decorators after this one in the new client
            callDecorators.subList(0, callDecorators.indexOf(this@HttpEngineCallDecorator) + 1).clear()
          }.build()
    }

    return HttpEngineCall(client.newCall(chain.request))
  }

  @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
  inner class HttpEngineCall(
    val realCall: Call,
  ) : Call by realCall {
    val httpEngine: HttpEngine
      get() = this@HttpEngineCallDecorator.httpEngine

    override fun cancel() {
      realCall.cancel()
      httpEngineInterceptor.cancelCall(realCall)
    }
  }

  companion object {
    val HttpEngine.callDecorator
      get() = HttpEngineCallDecorator(this)
  }
}

private fun isHttpEngineSupported(): Boolean =
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
