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
 */
package okhttp3

import kotlin.reflect.KClass
import okio.IOException
import okio.Timeout

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
interface Call : Cloneable {
  /** Returns the original request that initiated this call. */
  fun request(): Request

  /**
   * Invokes the request immediately, and blocks until the response can be processed or is in error.
   *
   * To avoid leaking resources callers should close the [Response] which in turn will close the
   * underlying [ResponseBody].
   *
   * ```java
   * // ensure the response (and underlying response body) is closed
   * try (Response response = client.newCall(request).execute()) {
   *   ...
   * }
   * ```
   *
   * The caller may read the response body with the response's [Response.body] method. To avoid
   * leaking resources callers must [close the response body][ResponseBody] or the response.
   *
   * Note that transport-layer success (receiving a HTTP response code, headers and body) does not
   * necessarily indicate application-layer success: `response` may still indicate an unhappy HTTP
   * response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   *     problem or timeout. Because networks can fail during an exchange, it is possible that the
   *     remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  @Throws(IOException::class)
  fun execute(): Response

  /**
   * Schedules the request to be executed at some point in the future.
   *
   * The [dispatcher][OkHttpClient.dispatcher] defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * This client will later call back `responseCallback` with either an HTTP response or a failure
   * exception.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  fun enqueue(responseCallback: Callback)

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  fun cancel()

  /**
   * Returns true if this call has been either [executed][execute] or [enqueued][enqueue]. It is an
   * error to execute a call more than once.
   */
  fun isExecuted(): Boolean

  fun isCanceled(): Boolean

  /**
   * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
   * body, server processing, and reading the response body. If the call requires redirects or
   * retries all must complete within one timeout period.
   *
   * Configure the client's default timeout with [OkHttpClient.Builder.callTimeout].
   */
  fun timeout(): Timeout

  /**
   * Configure this call to publish all future events to [eventListener], in addition to the
   * listeners configured by [OkHttpClient.Builder.eventListener] and other calls to this function.
   *
   * If this call is later [cloned][clone], [eventListener] will not be notified of its events.
   *
   * There is no mechanism to remove an event listener. Implementations should instead ignore events
   * that they are not interested in.
   *
   * @see EventListener for semantics and restrictions on listener implementations.
   */
  fun addEventListener(eventListener: EventListener)

  /**
   * Returns the tag attached with [type] as a key, or null if no tag is attached with that key.
   *
   * The tags on a call are seeded from the [request tags][Request.tag]. This set will grow if new
   * tags are computed.
   */
  fun <T : Any> tag(type: KClass<T>): T?

  /**
   * Returns the tag attached with [type] as a key, or null if no tag is attached with that key.
   *
   * The tags on a call are seeded from the [request tags][Request.tag]. This set will grow if new
   * tags are computed.
   */
  fun <T> tag(type: Class<out T>): T?

  /**
   * Returns the tag attached with [type] as a key. If it is absent, then [computeIfAbsent] is
   * called and that value is both inserted and returned.
   *
   * If multiple calls to this function are made concurrently with the same [type], multiple values
   * may be computed. But only one value will be inserted, and that inserted value will be returned
   * to all callers.
   *
   * If computing multiple values is problematic, use an appropriate concurrency mechanism in your
   * [computeIfAbsent] implementation. No locks are held while calling this function.
   */
  fun <T : Any> tag(
    type: KClass<T>,
    computeIfAbsent: () -> T,
  ): T

  /**
   * Returns the tag attached with [type] as a key. If it is absent, then [computeIfAbsent] is
   * called and that value is both inserted and returned.
   *
   * If multiple calls to this function are made concurrently with the same [type], multiple values
   * may be computed. But only one value will be inserted, and that inserted value will be returned
   * to all callers.
   *
   * If computing multiple values is problematic, use an appropriate concurrency mechanism in your
   * [computeIfAbsent] implementation. No locks are held while calling this function.
   */
  fun <T : Any> tag(
    type: Class<T>,
    computeIfAbsent: () -> T,
  ): T

  /**
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   *
   * The tags on the returned call will equal the tags as on [request]. Any tags that were computed
   * for this call will not be included on the cloned call. If necessary you may manually copy over
   * specific tags by re-computing them:
   *
   * ```kotlin
   * val copy = original.clone()
   *
   * val myTag = original.tag(MyTag::class)
   * if (myTag != null) {
   *   copy.tag(MyTag::class) { myTag }
   * }
   * ```
   *
   * ```java
   * Call copy = original.clone();
   *
   * MyTag myTag = original.tag(MyTag.class);
   * if (myTag != null) {
   *   copy.tag(MyTag.class, () -> myTag);
   * }
   * ```
   *
   * If any event listeners were installed on this call with [addEventListener], they will not be
   * installed on this copy.
   */
  public override fun clone(): Call

  fun interface Factory {
    fun newCall(request: Request): Call
  }
}
