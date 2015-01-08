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

package com.squareup.okhttp.android;

import com.squareup.okhttp.Cache;
import com.squareup.okhttp.AndroidShimResponseCache;
import com.squareup.okhttp.OkCacheContainer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

/**
 * A copy of android.net.http.HttpResponseCache taken from AOSP. Android need to keep this code
 * working somehow. Dependencies on com.squareup.okhttp are com.android.okhttp on Android.
 */
/* <p>This class exists in okhttp-android-support to help keep the API as it always has been on
 * Android. The public API cannot be changed. This class delegates to
 * {@link com.squareup.okhttp.AndroidShimResponseCache}, a class that exists in a package that
 * enables it to interact with non-public OkHttp classes.
 */
public final class HttpResponseCache extends ResponseCache implements Closeable, OkCacheContainer {

  private AndroidShimResponseCache shimResponseCache;

  private HttpResponseCache(AndroidShimResponseCache shimResponseCache) {
    this.shimResponseCache = shimResponseCache;
  }

  /**
   * Returns the currently-installed {@code HttpResponseCache}, or null if
   * there is no cache installed or it is not a {@code HttpResponseCache}.
   */
  public static HttpResponseCache getInstalled() {
    ResponseCache installed = ResponseCache.getDefault();
    if (installed instanceof HttpResponseCache) {
      return (HttpResponseCache) installed;
    }
    return null;
  }

  /**
   * Creates a new HTTP response cache and sets it as the system default cache.
   *
   * @param directory the directory to hold cache data.
   * @param maxSize the maximum size of the cache in bytes.
   * @return the newly-installed cache
   * @throws java.io.IOException if {@code directory} cannot be used for this cache.
   *     Most applications should respond to this exception by logging a
   *     warning.
   */
  public static synchronized HttpResponseCache install(File directory, long maxSize) throws
      IOException {
    ResponseCache installed = ResponseCache.getDefault();

    if (installed instanceof HttpResponseCache) {
      HttpResponseCache installedResponseCache = (HttpResponseCache) installed;
      // don't close and reopen if an equivalent cache is already installed
      AndroidShimResponseCache trueResponseCache = installedResponseCache.shimResponseCache;
      if (trueResponseCache.isEquivalent(directory, maxSize)) {
        return installedResponseCache;
      } else {
        // The HttpResponseCache that owns this object is about to be replaced.
        trueResponseCache.close();
      }
    }

    AndroidShimResponseCache trueResponseCache =
        AndroidShimResponseCache.create(directory, maxSize);
    HttpResponseCache newResponseCache = new HttpResponseCache(trueResponseCache);
    ResponseCache.setDefault(newResponseCache);
    return newResponseCache;
  }

  @Override public CacheResponse get(URI uri, String requestMethod,
      Map<String, List<String>> requestHeaders) throws IOException {
    return shimResponseCache.get(uri, requestMethod, requestHeaders);
  }

  @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
    return shimResponseCache.put(uri, urlConnection);
  }

  /**
   * Returns the number of bytes currently being used to store the values in
   * this cache. This may be greater than the {@link #maxSize} if a background
   * deletion is pending.
   */
  public long size() {
    try {
      return shimResponseCache.size();
    } catch (IOException e) {
      // This can occur if the cache failed to lazily initialize. Return -1 to mean "unknown".
      return -1;
    }
  }

  /**
   * Returns the maximum number of bytes that this cache should use to store
   * its data.
   */
  public long maxSize() {
    return shimResponseCache.maxSize();
  }

  /**
   * Force buffered operations to the filesystem. This ensures that responses
   * written to the cache will be available the next time the cache is opened,
   * even if this process is killed.
   */
  public void flush() {
    try {
      shimResponseCache.flush();
    } catch (IOException ignored) {
    }
  }

  /**
   * Returns the number of HTTP requests that required the network to either
   * supply a response or validate a locally cached response.
   */
  public int getNetworkCount() {
    return shimResponseCache.getNetworkCount();
  }

  /**
   * Returns the number of HTTP requests whose response was provided by the
   * cache. This may include conditional {@code GET} requests that were
   * validated over the network.
   */
  public int getHitCount() {
    return shimResponseCache.getHitCount();
  }

  /**
   * Returns the total number of HTTP requests that were made. This includes
   * both client requests and requests that were made on the client's behalf
   * to handle a redirects and retries.
   */
  public int getRequestCount() {
    return shimResponseCache.getRequestCount();
  }

  /**
   * Uninstalls the cache and releases any active resources. Stored contents
   * will remain on the filesystem.
   */
  @Override public void close() throws IOException {
    if (ResponseCache.getDefault() == this) {
      ResponseCache.setDefault(null);
    }
    shimResponseCache.close();
  }

  /**
   * Uninstalls the cache and deletes all of its stored contents.
   */
  public void delete() throws IOException {
    if (ResponseCache.getDefault() == this) {
      ResponseCache.setDefault(null);
    }
    shimResponseCache.delete();
  }

  @Override
  public Cache getCache() {
    return shimResponseCache.getCache();
  }

}
