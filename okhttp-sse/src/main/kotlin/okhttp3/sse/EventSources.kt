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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.sse.internal.RealEventSource

object EventSources {
  @Deprecated(
    message = "required for binary-compatibility!",
    level = DeprecationLevel.HIDDEN,
  )
  @JvmStatic
  fun createFactory(client: OkHttpClient) = createFactory(client as Call.Factory)

  @JvmStatic
  fun createFactory(callFactory: Call.Factory): EventSource.Factory {
    return EventSource.Factory { request, listener ->
      val actualRequest =
        if (request.header("Accept") == null) {
          request.newBuilder().addHeader("Accept", "text/event-stream").build()
        } else {
          request
        }

      RealEventSource(actualRequest, listener).apply {
        connect(callFactory)
      }
    }
  }

  @JvmStatic
  fun processResponse(response: Response, listener: EventSourceListener) {
    val eventSource = RealEventSource(response.request, listener)
    eventSource.processResponse(response)
  }
}
