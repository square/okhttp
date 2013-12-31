/*
 * Copyright (C) 2011 The Android Open Source Project
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

/** The source of an HTTP response. */
public enum ResponseSource {

  /** The response was returned from the local cache. */
  CACHE,

  /**
   * The response is available in the cache but must be validated with the
   * network. The cache result will be used if it is still valid; otherwise
   * the network's response will be used.
   */
  CONDITIONAL_CACHE,

  /** The response was returned from the network. */
  NETWORK,

  /**
   * The request demanded a cached response that the cache couldn't satisfy.
   * This yields a 504 (Gateway Timeout) response as specified by
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4.
   */
  NONE;

  public boolean requiresConnection() {
    return this == CONDITIONAL_CACHE || this == NETWORK;
  }
}
