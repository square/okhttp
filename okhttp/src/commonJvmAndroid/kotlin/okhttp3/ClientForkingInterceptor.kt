package okhttp3

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.internal.connection.RealCall

@ExperimentalOkHttpApi
// Inspiration from https://publicobject.com/2017/04/02/a-clever-flawed-okhttp-interceptor-hack/
// But behind an experimental but official API
abstract class ClientForkingInterceptor<K : Any> : Interceptor {
  // TODO consider caching by client and cleaning up
  private val forkedClients = ConcurrentHashMap<K, OkHttpClient>()

  // TODO consider whether we need to address lifecycle for cleanup of clients
//  override fun onNewClientInstance(client: OkHttpClient): Interceptor {
//    return this
//  }

  fun removeClient(key: K) {
    forkedClients.remove(key)
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val client =
      (chain.call() as? RealCall)?.client ?: throw IOException("unable to access OkHttpClient")

    val key = clientKey(chain.request())

    if (key == null) {
      return chain.proceed(chain.request())
    } else {
      val override = forkedClients.getOrPut(key) { client.newBuilder().buildForKey(key) }
      return override.newCall(chain.request()).execute()
    }
  }

  abstract fun clientKey(request: Request): K?

  abstract fun OkHttpClient.Builder.buildForKey(key: K): OkHttpClient
}
