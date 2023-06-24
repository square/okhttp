package okhttp3;

import kotlinx.coroutines.runBlocking

fun interface SuspendingInterceptor: Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
    interceptAsync(chain)
  }

  suspend fun interceptAsync(chain: Interceptor.Chain): Response
}
