package okhttp3;

import kotlinx.coroutines.runBlocking

abstract class SuspendingInterceptor: Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
    interceptAsync(chain)
  }

  abstract suspend fun interceptAsync(chain: Interceptor.Chain): Response
}
