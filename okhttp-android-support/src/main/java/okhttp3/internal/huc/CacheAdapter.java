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
package okhttp3.internal.huc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URI;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.InternalCache;
import okhttp3.internal.http.CacheRequest;
import okhttp3.internal.http.CacheStrategy;
import okio.Okio;
import okio.Sink;

/** Adapts {@link ResponseCache} to {@link InternalCache}. */
public final class CacheAdapter implements InternalCache {
  private final ResponseCache delegate;

  public CacheAdapter(ResponseCache delegate) {
    this.delegate = delegate;
  }

  public ResponseCache getDelegate() {
    return delegate;
  }

  @Override public Response get(Request request) throws IOException {
    CacheResponse javaResponse = getJavaCachedResponse(request);
    if (javaResponse == null) {
      return null;
    }
    return JavaApiConverter.createOkResponseForCacheGet(request, javaResponse);
  }

  @Override public CacheRequest put(Response response) throws IOException {
    URI uri = response.request().url().uri();
    HttpURLConnection connection = JavaApiConverter.createJavaUrlConnectionForCachePut(response);
    final java.net.CacheRequest request = delegate.put(uri, connection);
    if (request == null) {
      return null;
    }
    return new CacheRequest() {
      @Override public Sink body() throws IOException {
        OutputStream body = request.getBody();
        return body != null ? Okio.sink(body) : null;
      }

      @Override public void abort() {
        request.abort();
      }
    };
  }

  @Override public void remove(Request request) throws IOException {
    // This method is treated as optional and there is no obvious way of implementing it with
    // ResponseCache. Removing items from the cache due to modifications made from this client is
    // not essential given that modifications could be made from any other client. We have to assume
    // that it's ok to keep using the cached data. Otherwise the server shouldn't declare it as
    // cacheable or the client should be careful about caching it.
  }

  @Override public void update(Response cached, Response network) throws IOException {
    // This method is treated as optional and there is no obvious way of implementing it with
    // ResponseCache. Updating headers is useful if the server changes the metadata for a resource
    // (e.g. max age) to extend or truncate the life of that resource in the cache. If the metadata
    // is not updated the caching behavior may not be optimal, but will obey the metadata sent
    // with the original cached response.
  }

  @Override public void trackConditionalCacheHit() {
    // This method is optional.
  }

  @Override public void trackResponse(CacheStrategy cacheStrategy) {
    // This method is optional.
  }

  /**
   * Returns the {@link CacheResponse} from the delegate by converting the OkHttp {@link Request}
   * into the arguments required by the {@link ResponseCache}.
   */
  private CacheResponse getJavaCachedResponse(Request request) throws IOException {
    Map<String, List<String>> headers = JavaApiConverter.extractJavaHeaders(request);
    return delegate.get(request.url().uri(), request.method(), headers);
  }
}
