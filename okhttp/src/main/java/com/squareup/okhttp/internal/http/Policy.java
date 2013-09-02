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
package com.squareup.okhttp.internal.http;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

public interface Policy {
  /** Returns true if HTTP response caches should be used. */
  boolean getUseCaches();

  /** Returns the HttpURLConnection instance to store in the cache. */
  HttpURLConnection getHttpConnectionToCache();

  /** Returns the current destination URL, possibly a redirect. */
  URL getURL();

  /** Returns the If-Modified-Since timestamp, or 0 if none is set. */
  long getIfModifiedSince();

  /** Returns true if a non-direct proxy is specified. */
  boolean usingProxy();

  /** @see java.net.HttpURLConnection#setChunkedStreamingMode(int) */
  int getChunkLength();

  /** @see java.net.HttpURLConnection#setFixedLengthStreamingMode(int) */
  long getFixedContentLength();

  /**
   * Sets the current proxy that this connection is using.
   * @see java.net.HttpURLConnection#usingProxy
   */
  void setSelectedProxy(Proxy proxy);
}
