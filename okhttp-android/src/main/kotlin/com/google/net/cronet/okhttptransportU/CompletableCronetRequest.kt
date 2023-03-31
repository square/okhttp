package com.google.net.cronet.okhttptransportU

import okhttp3.Interceptor
import okhttp3.Request

class CompletableCronetRequest(
  private val chain: Interceptor.Chain,

) {
  private val redirectStrategy: RedirectStrategy = chain.call().c
  val callback = OkHttpBridgeRequestCallback(chain.readTimeoutMillis().toLong(), redirectStrategy)
}
