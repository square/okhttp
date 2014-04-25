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
package com.squareup.okhttp;

/**
 * A call is an asynchronous {@code request} that has been prepared for
 * execution. Once executed, a call can be cancelled. As this object represents
 * a single request/response pair (or stream), it cannot be executed twice.
 */
public final class Call {
  private final OkHttpClient client;
  private final Dispatcher dispatcher;
  private final Request request;

  public Call(OkHttpClient client, Dispatcher dispatcher,
      Request request) {
    this.client = client;
    this.dispatcher = dispatcher;
    this.request = request;
  }

  /**
   * Schedules the {@code request} to be executed at some point in the future.
   * The {@link OkHttpClient#getDispatcher dispatcher} defines when the request
   * will run: usually immediately unless there are several other requests
   * currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either
   * an HTTP response or a failure exception. If you {@link #cancel} a request
   * before it completes the callback will not be invoked.
   */
  public void execute(Response.Callback responseCallback) {
    dispatcher.enqueue(client, request, responseCallback);
  }

  /**
   * Cancels the request, if possible. Requests that are already complete cannot
   * be canceled.
   */
  public void cancel() {
    dispatcher.cancel(request.tag());
  }
}
