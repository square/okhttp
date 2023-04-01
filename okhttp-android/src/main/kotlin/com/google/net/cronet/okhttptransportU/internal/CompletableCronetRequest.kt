package com.google.net.cronet.okhttptransportU.internal

import android.net.http.UrlRequest
import androidx.annotation.RequiresApi
import com.google.net.cronet.okhttptransportU.RedirectStrategy
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.connection.RealCall

@RequiresApi(34)
class CompletableCronetRequest(
  private val chain: Interceptor.Chain,
  private val responseConverter: ResponseConverter,
) {
  // TODO use clean API from https://github.com/square/okhttp/pull/7728
  private val redirectStrategy: RedirectStrategy = chain.call().redirectStrategy()

  private lateinit var cronetRequest: UrlRequest

  val callback = OkHttpBridgeRequestCallback(chain.readTimeoutMillis().toLong(), redirectStrategy)

  fun cancel() {
    cronetRequest.cancel()
  }

  fun start() {
    cronetRequest.start()
  }

  fun response(): Response {
    return runBlocking {
      responseConverter.toResponse(chain.request(), callback)
    }
  }

  private fun Call.redirectStrategy() =
    if ((this as RealCall).client.followRedirects)
      RedirectStrategy.defaultStrategy()
    else
      RedirectStrategy.withoutRedirects()

  fun setRequest(cronetRequest: UrlRequest) {
    this.cronetRequest = cronetRequest
  }
}
