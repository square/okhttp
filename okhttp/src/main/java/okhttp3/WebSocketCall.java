/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

public interface WebSocketCall extends Cloneable {
  /** Returns the original request that initiated this call. */
  Request request();

  /**
   * Schedules the request to be executed at some point in the future.
   *
   * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
   * failure exception. If you {@link #cancel} a request before it completes the callback will not
   * be invoked.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  void enqueue(WebSocketListener listener);

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  void cancel();

  /**
   * Returns true if this call has been {@linkplain #enqueue(WebSocketListener) enqueued}. It is an
   * error to enqueue a call more than once.
   */
  boolean isExecuted();

  boolean isCanceled();

  /**
   * Create a new, identical call to this one which can be enqueued even if this call has already
   * been.
   */
  WebSocketCall clone();

  interface Factory {
    WebSocketCall newWebSocketCall(Request request);
  }
}
