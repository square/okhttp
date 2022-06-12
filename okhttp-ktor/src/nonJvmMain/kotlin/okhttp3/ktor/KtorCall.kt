/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.CancellationException
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.IOException

class KtorCall(private val httpClient: HttpClient, private val request: Request) : Call {
  val job = Job()
  val scope = CoroutineScope(Dispatchers.Default + job)
  private val executed = atomic(false)

  override fun request(): Request = request

  override suspend fun executeAsync(): Response {
    check(executed.compareAndSet(expect = false, update = true)) { "Already Executed" }

    return withContext(scope.coroutineContext) {
      try {
        val httpResponse = httpClient.request(request.toHttpRequestBuilder())
        httpResponse.toResponse(request)
      } catch (ioe: IOException) {
        throw ioe
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        throw IOException("request failed", e)
      }
    }
  }

  override fun enqueue(responseCallback: Callback) {
    scope.launch {
      try {
        val response = executeAsync()
        responseCallback.onResponse(this@KtorCall, response)
      } catch (e: IOException) {
        responseCallback.onFailure(this@KtorCall, e)
      } catch (e: Exception) {
        responseCallback.onFailure(this@KtorCall, IOException("non IOException during call: ", e))
      }
    }
  }

  override fun cancel() {
    job.cancel(CancellationException("KtorCall cancellation"))
  }

  override fun isExecuted(): Boolean = executed.value

  override fun isCanceled(): Boolean = job.isCancelled

  override fun clone(): Call = KtorCall(httpClient, request)
}

