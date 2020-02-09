/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.checkDuration
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 *
 * If the chain is for an application interceptor then [exchange] must be null. Otherwise it is for
 * a network interceptor and [exchange] must be non-null.
 */
class RealInterceptorChain(
  internal val call: RealCall,
  private val interceptors: List<Interceptor>,
  private val index: Int,
  internal val exchange: Exchange?,
  internal val request: Request,
  internal val connectTimeoutMillis: Int,
  internal val readTimeoutMillis: Int,
  internal val writeTimeoutMillis: Int
) : Interceptor.Chain {

  private var calls: Int = 0

  internal fun copy(
    index: Int = this.index,
    exchange: Exchange? = this.exchange,
    request: Request = this.request,
    connectTimeoutMillis: Int = this.connectTimeoutMillis,
    readTimeoutMillis: Int = this.readTimeoutMillis,
    writeTimeoutMillis: Int = this.writeTimeoutMillis
  ) = RealInterceptorChain(call, interceptors, index, exchange, request, connectTimeoutMillis,
      readTimeoutMillis, writeTimeoutMillis)

  override fun connection(): Connection? = exchange?.connection

  override fun connectTimeoutMillis(): Int = connectTimeoutMillis

  override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
      copy(connectTimeoutMillis = checkDuration("connectTimeout", timeout.toLong(), unit))

  override fun readTimeoutMillis(): Int = readTimeoutMillis

  override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
      copy(readTimeoutMillis = checkDuration("readTimeout", timeout.toLong(), unit))

  override fun writeTimeoutMillis(): Int = writeTimeoutMillis

  override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
      copy(writeTimeoutMillis = checkDuration("writeTimeout", timeout.toLong(), unit))

  override fun call(): Call = call

  override fun request(): Request = request

  @Throws(IOException::class)
  override fun proceed(request: Request): Response {
    check(index < interceptors.size)

    calls++

    if (exchange != null) {
      check(exchange.connection.supportsUrl(request.url)) {
        "network interceptor ${interceptors[index - 1]} must retain the same host and port"
      }
      check(calls == 1) {
        "network interceptor ${interceptors[index - 1]} must call proceed() exactly once"
      }
    }

    // Call the next interceptor in the chain.
    val next = copy(index = index + 1, request = request)
    val interceptor = interceptors[index]

    @Suppress("USELESS_ELVIS")
    val response = interceptor.intercept(next) ?: throw NullPointerException(
        "interceptor $interceptor returned null")

    if (exchange != null) {
      check(index + 1 >= interceptors.size || next.calls == 1) {
        "network interceptor $interceptor must call proceed() exactly once"
      }
    }

    check(response.body != null) { "interceptor $interceptor returned a response with no body" }

    return response
  }
}
