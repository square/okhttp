/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.mockwebserver

import mockwebserver3.QueueDispatcher

class QueueDispatcher : Dispatcher() {
  internal val delegate = QueueDispatcher()

  @Throws(InterruptedException::class)
  override fun dispatch(request: RecordedRequest): MockResponse {
    throw UnsupportedOperationException("unexpected call")
  }

  override fun peek(): MockResponse {
    throw UnsupportedOperationException("unexpected call")
  }

  fun enqueueResponse(response: MockResponse) {
    delegate.enqueueResponse(response.wrap())
  }

  override fun shutdown() {
    delegate.shutdown()
  }

  fun setFailFast(failFast: Boolean) {
    delegate.setFailFast(failFast)
  }

  fun setFailFast(failFastResponse: MockResponse?) {
    delegate.setFailFast(failFastResponse?.wrap())
  }
}
