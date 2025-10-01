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
package okhttp3.sse.internal

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.stripBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.AsyncTimeout
import okio.Timeout.Companion.timeout

internal class RealEventSource private constructor(
  private val call: Call?,
  private val request: Request,
  private val listener: EventSourceListener,
) : EventSource,
  ServerSentEventReader.Callback,
  Callback {
  constructor(call: Call, listener: EventSourceListener) : this(call, call.request(), listener)

  constructor(response: Response, listener: EventSourceListener) : this(null, response.request, listener)

  @Volatile private var canceled = false

  private fun updateTimeout(call: Call?, duration: Duration) {
    val timeout = call?.timeout()
    if (timeout is AsyncTimeout) {
      timeout.apply {
        // If a timeout is in process, we exit it before entering again
        if (this.timeoutNanos() > 0L) {
          exit()
        }
        timeout(duration)
        enter()
      }
    }
  }

  override fun onResponse(
    call: Call,
    response: Response,
  ) {
    processResponse(response)
  }

  internal fun processResponse(response: Response) {
    response.use {
      if (!response.isSuccessful) {
        listener.onFailure(this, null, response)
        return
      }

      val body = response.body

      if (!body.isEventStream()) {
        listener.onFailure(
          this,
          IllegalStateException("Invalid content-type: ${body.contentType()}"),
          response,
        )
        return
      }

      // This is a long-lived response. Cancel full-call timeouts if no timeout has been set
      listener.idleTimeoutMillis?.let {
        // We spend at most timeout seconds if set
        updateTimeout(call, it.milliseconds)
      } ?: call?.timeout()?.cancel()

      // Replace the body with a stripped one so the callbacks can't see real data.
      val response = response.stripBody()

      val reader = ServerSentEventReader(body.source(), this)
      try {
        if (!canceled) {
          listener.onOpen(this, response)
          while (!canceled && reader.processNextEvent()) {
            listener.idleTimeoutMillis?.let {
              // We spend at most timeout seconds if set
              updateTimeout(call, it.milliseconds)
            }
          }
        }
      } catch (e: Exception) {
        val exception =
          when {
            canceled -> IOException("canceled", e)
            else -> e
          }
        listener.onFailure(this, exception, response)
        return
      }
      if (canceled) {
        listener.onFailure(this, IOException("canceled"), response)
      } else {
        listener.onClosed(this)
      }
    }
  }

  private fun ResponseBody.isEventStream(): Boolean {
    val contentType = contentType() ?: return false
    return contentType.type == "text" && contentType.subtype == "event-stream"
  }

  override fun onFailure(
    call: Call,
    e: IOException,
  ) {
    listener.onFailure(this, e, null)
  }

  override fun request(): Request = request

  override fun cancel() {
    canceled = true
    call?.cancel()
  }

  override fun onEvent(
    id: String?,
    type: String?,
    data: String,
  ) {
    listener.onEvent(this, id, type, data)
  }

  override fun onRetryChange(timeMs: Long) {
    // Ignored. We do not auto-retry.
  }
}
