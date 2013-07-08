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

import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.ResponseSource;
import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

public final class OkResponseCacheAdapter implements OkResponseCache {
  private final ResponseCache responseCache;
  public OkResponseCacheAdapter(ResponseCache responseCache) {
    this.responseCache = responseCache;
  }

  @Override public CacheResponse get(URI uri, String requestMethod,
      Map<String, List<String>> requestHeaders) throws IOException {
    return responseCache.get(uri, requestMethod, requestHeaders);
  }

  @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
    return responseCache.put(uri, urlConnection);
  }

  @Override public void maybeRemove(String requestMethod, URI uri) throws IOException {
  }

  @Override public void update(CacheResponse conditionalCacheHit, HttpURLConnection connection)
      throws IOException {
  }

  @Override public void trackConditionalCacheHit() {
  }

  @Override public void trackResponse(ResponseSource source) {
  }
}
