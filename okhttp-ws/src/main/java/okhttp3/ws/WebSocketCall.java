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
package okhttp3.ws;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.internal.ws.RealWebSocketCall;

public abstract class WebSocketCall {
  /**
   * Prepares the {@code request} to create a web socket at some point in the future.
   */
  public static WebSocketCall create(OkHttpClient client, Request request) {
    return new RealWebSocketCall(client, request);
  }

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
  public abstract void enqueue(WebSocketListener listener);

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  public abstract void cancel();
}
