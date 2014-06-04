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
package com.squareup.okhttp.internal.spdy;

import java.util.List;

/**
 * {@link com.squareup.okhttp.Protocol#HTTP_2 HTTP/2} only.
 * Processes server-initiated HTTP requests on the client. Implementations must
 * quickly dispatch callbacks to avoid creating a bottleneck.
 *
 * <p>While {@link #onReset} may occur at any time, the following callbacks are
 * expected in order, correlated by stream ID.
 * <ul>
 *   <li>{@link #onRequest}</li>
 *   <li>{@link #onHeaders} (unless canceled)</li>
 *   <li>{@link #onData} (optional sequence of data frames)</li>
 * </ul>
 *
 * <p>As a stream ID is scoped to a single HTTP/2 connection, implementations
 * which target multiple connections should expect repetition of stream IDs.
 *
 * <p>Return true to request cancellation of a pushed stream.  Note that this
 * does not guarantee future frames won't arrive on the stream ID.
 */
public interface SpdyPushObserver {
  /**
   * Describes the request that the server intends to push a response for.
   *
   * @param streamId server-initiated stream ID: an even number.
   * @param requestHeaders minimally includes {@code :method}, {@code :scheme},
   * {@code :authority}, and (@code :path}.
   */
  boolean onPromise(int streamId, List<Header> requestHeaders);

  /**
   * The response headers corresponding to a pushed request.  When {@code last}
   * is true, there are no data frames to follow.
   *
   * @param streamId server-initiated stream ID: an even number.
   * @param responseHeaders minimally includes {@code :status}.
   * @param last when true, there is no response data.
   */
  boolean onPush(SpdyStream associated, SpdyStream push);

  SpdyPushObserver CANCEL = new SpdyPushObserver() {

    @Override public boolean onPromise(int streamId, List<Header> requestHeaders) {
      return true;
    }

    @Override public boolean onPush(SpdyStream associated, SpdyStream push) {
      return true;
    }
  };
}
