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

import com.squareup.okhttp.ResponseSource;
import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

/**
 * An extended response cache API. Unlike {@link java.net.ResponseCache}, this
 * interface supports conditional caching and statistics.
 *
 * <p>Along with the rest of the {@code internal} package, this is not a public
 * API. Applications wishing to supply their own caches must use the more
 * limited {@link java.net.ResponseCache} interface.
 */
public interface OkResponseCache {
  CacheResponse get(URI uri, String requestMethod, Map<String, List<String>> requestHeaders)
      throws IOException;

  CacheRequest put(URI uri, URLConnection urlConnection) throws IOException;

  /**
   * Handles a conditional request hit by updating the stored cache response
   * with the headers from {@code httpConnection}. The cached response body is
   * not updated. If the stored response has changed since {@code
   * conditionalCacheHit} was returned, this does nothing.
   */
  void update(CacheResponse conditionalCacheHit, HttpURLConnection connection) throws IOException;

  /** Track an conditional GET that was satisfied by this cache. */
  void trackConditionalCacheHit();

  /** Track an HTTP response being satisfied by {@code source}. */
  void trackResponse(ResponseSource source);
}
