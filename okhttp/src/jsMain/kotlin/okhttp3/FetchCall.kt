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

package okhttp3

import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlinx.coroutines.*
import okio.IOException
import okio.Timeout
import okio.Timeout.Companion.NONE

class FetchCall(private val callRequest: Request, val coroutineScope: CoroutineScope) : Call {
  private var job: Job? = null
  private var canceled: Boolean = false
  private var executed: Boolean = false

  override fun request(): Request {
    return callRequest
  }

  override fun execute(): Response {
    throw UnsupportedOperationException()
  }

  override fun enqueue(responseCallback: Callback) {
    check(!executed) { "Already executed" }

    if (canceled) {
      responseCallback.onFailure(this, IOException("Canceled"))
    }

    executed = true

    job = coroutineScope.launch(Dispatchers.Main) {
      try {
        val response = executeRequest()
        responseCallback.onResponse(this@FetchCall, response)
      } catch (ioe: IOException) {
        responseCallback.onFailure(this@FetchCall, ioe)
      }
    }
  }

  suspend fun executeRequest(): Response {
    return suspendCoroutine { continuation ->
      // TODO use suspendCancellableCoroutine and
      // AbortController required for fetch
      // https://github.com/ktorio/ktor/blob/0081f943b434bdd0afd82424b389629e89a89461/ktor-client/ktor-client-core/js/src/io/ktor/client/fetch/LibDom.kt
      // https://youtrack.jetbrains.com/issue/KTOR-1460

      val promise: Promise<Response> = if (PlatformUtils.IS_BROWSER) {
        browserFetch(callRequest)
      } else {
        nodeFetchExecute(callRequest)
      }

      promise.then(
        onFulfilled = {
          continuation.resumeWith(Result.success(it))
        },
        onRejected = {
          continuation.resumeWith(Result.failure(Error(it.message, it)))
        }
      )
    }
  }

  override fun cancel() {
    canceled = true
    job?.cancel()
  }

  override fun isExecuted(): Boolean = executed

  override fun isCanceled(): Boolean = canceled

  override fun timeout(): Timeout = NONE

  override fun clone(): Call = FetchCall(callRequest, coroutineScope)
}
