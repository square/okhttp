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

import okhttp3.internal.jsExecuteAsync
import okio.IOException

class TestCall(
  private val request: Request,
  private val callbackHandler: (Call, Callback) -> Unit
) : Call {
  var cancelled = false
  var executed = false

  override fun request(): Request = request

  override suspend fun executeAsync(): Response = jsExecuteAsync()

  override fun enqueue(responseCallback: Callback) {
    check(!executed) { "Already Executed" }

    if (cancelled) {
      responseCallback.onFailure(this, IOException("Canceled"))
    } else {
      callbackHandler.invoke(this, responseCallback)
    }
  }

  override fun cancel() {
    cancelled = true
  }

  override fun isExecuted(): Boolean {
    return executed
  }

  override fun isCanceled(): Boolean {
    return cancelled
  }

  override fun clone(): Call {
    return TestCall(request, callbackHandler)
  }
}
