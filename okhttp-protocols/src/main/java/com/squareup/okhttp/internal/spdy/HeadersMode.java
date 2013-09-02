/*
 * Copyright (C) 2013 Square, Inc.
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

enum HeadersMode {
  SPDY_SYN_STREAM,
  SPDY_REPLY,
  SPDY_HEADERS,
  HTTP_20_HEADERS;

  /** Returns true if it is an error these headers to create a new stream. */
  public boolean failIfStreamAbsent() {
    return this == SPDY_REPLY || this == SPDY_HEADERS;
  }

  /** Returns true if it is an error these headers to update an existing stream. */
  public boolean failIfStreamPresent() {
    return this == SPDY_SYN_STREAM;
  }

  /**
   * Returns true if it is an error these headers to be the initial headers of a
   * response.
   */
  public boolean failIfHeadersAbsent() {
    return this == SPDY_HEADERS;
  }

  /**
   * Returns true if it is an error these headers to be update existing headers
   * of a response.
   */
  public boolean failIfHeadersPresent() {
    return this == SPDY_REPLY;
  }
}
