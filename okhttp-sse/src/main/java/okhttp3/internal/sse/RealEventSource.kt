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
package okhttp3.internal.sse

import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.connection.Exchange
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.io.IOException

class RealEventSource(
  private val request: Request,
  private val listener: EventSourceListener
) : EventSource, ServerSentEventReader.Callback, Callback {
  private lateinit var call: Call

  fun connect(client: OkHttpClient) {
    val client = client.newBuilder()
        .eventListener(EventListener.NONE)
        .build()
    call = client.newCall(request)
    call.enqueue(this)
  }

  override fun onResponse(call: Call, response: Response) {
    processResponse(response)
  }

  fun processResponse(response: Response) {
    response.use {
      if (!response.isSuccessful) {
        listener.onFailure(this, null, response)
        return
      }

      val body = response.body!!

      if (!body.isEventStream()) {
        listener.onFailure(this,
            IllegalStateException("Invalid content-type: ${body.contentType()}"), response)
        return
      }

      // This is a long-lived response. Cancel full-call timeouts.
      Exchange.get(response)?.timeoutEarlyExit()

      // Replace the body with an empty one so the callbacks can't see real data.
      val response = response.newBuilder()
          .body(EMPTY_RESPONSE)
          .build()

      val reader = ServerSentEventReader(body.source(), this)
      try {
        listener.onOpen(this, response)
        while (reader.processNextEvent()) {
        }
      } catch (e: Exception) {
        listener.onFailure(this, e, response)
        return
      }
      listener.onClosed(this)
    }
  }

  private fun ResponseBody.isEventStream(): Boolean {
    val contentType = contentType() ?: return false
    return contentType.type == "text" && contentType.subtype == "event-stream"
  }

  override fun onFailure(call: Call, e: IOException) {
    listener.onFailure(this, e, null)
  }

  override fun request(): Request = request

  override fun cancel() {
    call.cancel()
  }

  override fun onEvent(id: String?, type: String?, data: String) {
    listener.onEvent(this, id, type, data)
  }

  override fun onRetryChange(timeMs: Long) {
    // Ignored. We do not auto-retry.
  }
}
