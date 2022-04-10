package okhttp.android.envoy

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

abstract class CoroutineInterceptor : Interceptor {
  final override fun intercept(chain: Interceptor.Chain): Response {
    return runBlocking {
      try {
        interceptSuspend(chain)
      } catch (ce: CancellationException) {
        throw IOException(ce)
      }
    }
  }

  abstract suspend fun interceptSuspend(chain: Interceptor.Chain): Response
}
