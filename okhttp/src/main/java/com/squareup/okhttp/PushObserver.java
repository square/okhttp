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
 * {@link com.squareup.okhttp.Protocol#HTTP_2 HTTP/2} and
 * {@link com.squareup.okhttp.Protocol#SPDY_3 SPDY/3} only.
 * Processes server-initiated HTTP requests on the client. Implementations must
 * quickly dispatch callbacks to avoid creating a bottleneck.
 *
 * <p>Return true to request cancellation of a pushed stream.  Note that this
 * does not guarantee future frames won't arrive on the stream ID.
 */
public interface PushObserver {
  /**
   * Receive a push initiated by the server. The push is in the form of
   * an http response, where the request is either a previous client response
   * (SPDY) or a push promise "request" from the server (HTTP/2).
   *
   * @param response The push from the server
   * @return true to cancel the push stream
   */
  boolean onPush(Response response);
}
