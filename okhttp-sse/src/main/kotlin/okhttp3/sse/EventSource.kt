/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.sse

import okhttp3.Request

interface EventSource {
  /** Returns the original request that initiated this event source. */
  fun request(): Request

  /**
   * Immediately and violently release resources held by this event source. This does nothing if
   * the event source has already been closed or canceled.
   */
  fun cancel()

  interface Factory {
    /**
     * Creates a new event source and immediately returns it. Creating an event source initiates an
     * asynchronous process to connect the socket. Once that succeeds or fails, `listener` will be
     * notified. The caller must cancel the returned event source when it is no longer in use.
     */
    fun newEventSource(request: Request, listener: EventSourceListener): EventSource
  }
}
