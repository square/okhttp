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

import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.internal.RealEventSource

interface EventSource {
  /** Returns the original request that initiated this event source. */
  fun request(): Request

  /**
   * Immediately and violently release resources held by this event source. This does nothing if
   * the event source has already been closed or canceled.
   */
  fun cancel()

  fun interface Factory {
    /**
     * Creates a new event source and immediately returns it. Creating an event source initiates an
     * asynchronous process to connect the socket. Once that succeeds or fails, `listener` will be
     * notified. The caller must cancel the returned event source when it is no longer in use.
     */
    fun newEventSource(
      request: Request,
      listener: EventSourceListener,
    ): EventSource

    companion object {
      /**
       * Wraps a [Call.Factory] into [EventSource.Factory].
       */
      @JvmStatic
      @JvmName("create")
      fun Call.Factory.asEventSourceFactory(): Factory =
        Factory { request, listener ->
          val actualRequest =
            if (request.header("Accept") == null) {
              request.newBuilder().addHeader("Accept", "text/event-stream").build()
            } else {
              request
            }

          this.newCall(actualRequest).toEventSource(listener)
        }
    }
  }

  companion object {
    /**
     * Creates a new [EventSource] from the [Call] and immediately enqueue it.
     */
    @JvmStatic
    @JvmName("create")
    fun Call.toEventSource(listener: EventSourceListener): EventSource = RealEventSource(this, listener).also(this::enqueue)

    /**
     * Creates a new [EventSource] from the existing [Response].
     */
    @JvmStatic
    @JvmName("create")
    fun Response.toEventSource(listener: EventSourceListener): EventSource = RealEventSource(this, listener)
  }
}
