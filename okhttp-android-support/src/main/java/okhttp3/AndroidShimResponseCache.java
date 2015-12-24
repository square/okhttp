/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3;

import java.io.File;
import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import okhttp3.internal.huc.JavaApiConverter;

/**
 * A class provided for use by Android so that it can continue supporting a {@link ResponseCache}
 * with stats.
 */
public class AndroidShimResponseCache extends ResponseCache {

  private final Cache delegate;

  private AndroidShimResponseCache(Cache delegate) {
    this.delegate = delegate;
  }

  public static AndroidShimResponseCache create(File directory, long maxSize) throws IOException {
    Cache cache = new Cache(directory, maxSize);
    return new AndroidShimResponseCache(cache);
  }

  public boolean isEquivalent(File directory, long maxSize) {
    Cache installedCache = getCache();
    return (installedCache.getDirectory().equals(directory)
        && installedCache.getMaxSize() == maxSize
        && !installedCache.isClosed());
  }

  public Cache getCache() {
    return delegate;
  }

  @Override public CacheResponse get(URI uri, String requestMethod,
      Map<String, List<String>> requestHeaders) throws IOException {
    Request okRequest = JavaApiConverter.createOkRequest(uri, requestMethod, requestHeaders);
    Response okResponse = delegate.internalCache.get(okRequest);
    if (okResponse == null) {
      return null;
    }
    return JavaApiConverter.createJavaCacheResponse(okResponse);
  }

  @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
    Response okResponse = JavaApiConverter.createOkResponseForCachePut(uri, urlConnection);
    if (okResponse == null) {
      // The URLConnection is not cacheable or could not be converted. Stop.
      return null;
    }
    okhttp3.internal.http.CacheRequest okCacheRequest =
        delegate.internalCache.put(okResponse);
    if (okCacheRequest == null) {
      return null;
    }
    return JavaApiConverter.createJavaCacheRequest(okCacheRequest);
  }

  /**
   * Returns the number of bytes currently being used to store the values in this cache. This may be
   * greater than the {@link #maxSize} if a background deletion is pending.
   */
  public long size() throws IOException {
    return delegate.getSize();
  }

  /**
   * Returns the maximum number of bytes that this cache should use to store its data.
   */
  public long maxSize() {
    return delegate.getMaxSize();
  }

  /**
   * Force buffered operations to the filesystem. This ensures that responses written to the cache
   * will be available the next time the cache is opened, even if this process is killed.
   */
  public void flush() throws IOException {
    delegate.flush();
  }

  /**
   * Returns the number of HTTP requests that required the network to either supply a response or
   * validate a locally cached response.
   */
  public int getNetworkCount() {
    return delegate.getNetworkCount();
  }

  /**
   * Returns the number of HTTP requests whose response was provided by the cache. This may include
   * conditional {@code GET} requests that were validated over the network.
   */
  public int getHitCount() {
    return delegate.getHitCount();
  }

  /**
   * Returns the total number of HTTP requests that were made. This includes both client requests
   * and requests that were made on the client's behalf to handle a redirects and retries.
   */
  public int getRequestCount() {
    return delegate.getRequestCount();
  }

  /**
   * Uninstalls the cache and releases any active resources. Stored contents will remain on the
   * filesystem.
   */
  public void close() throws IOException {
    delegate.close();
  }

  /**
   * Uninstalls the cache and deletes all of its stored contents.
   */
  public void delete() throws IOException {
    delegate.delete();
  }
}
