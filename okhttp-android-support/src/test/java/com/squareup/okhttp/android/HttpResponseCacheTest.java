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

import com.squareup.okhttp.AndroidInternal;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * A port of Android's android.net.http.HttpResponseCacheTest to JUnit4.
 */
public final class HttpResponseCacheTest {

  @Rule public TemporaryFolder cacheRule = new TemporaryFolder();
  @Rule public MockWebServer server = new MockWebServer();

  private File cacheDir;
  private OkUrlFactory client;

  @Before public void setUp() throws Exception {
    cacheDir = cacheRule.getRoot();
    client = new OkUrlFactory(new OkHttpClient());
  }

  @After public void tearDown() throws Exception {
    ResponseCache.setDefault(null);
  }

  @Test public void install() throws Exception {
    HttpResponseCache installed = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    assertNotNull(installed);
    assertSame(installed, ResponseCache.getDefault());
    assertSame(installed, HttpResponseCache.getDefault());
  }

  @Test public void secondEquivalentInstallDoesNothing() throws Exception {
    HttpResponseCache first = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    HttpResponseCache another = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    assertSame(first, another);
  }

  @Test public void installClosesPreviouslyInstalled() throws Exception {
    HttpResponseCache first = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    initializeCache(first);

    HttpResponseCache another = HttpResponseCache.install(cacheDir, 8 * 1024 * 1024);
    initializeCache(another);

    assertNotSame(first, another);
    try {
      first.flush();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void getInstalledWithWrongTypeInstalled() {
    ResponseCache.setDefault(new ResponseCache() {
      @Override
      public CacheResponse get(URI uri, String requestMethod,
          Map<String, List<String>> requestHeaders) {
        return null;
      }

      @Override
      public CacheRequest put(URI uri, URLConnection connection) {
        return null;
      }
    });
    assertNull(HttpResponseCache.getInstalled());
  }

  @Test public void closeCloses() throws Exception {
    HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    initializeCache(cache);

    cache.close();
    try {
      cache.flush();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void closeUninstalls() throws Exception {
    HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    cache.close();
    assertNull(ResponseCache.getDefault());
  }

  @Test public void deleteUninstalls() throws Exception {
    HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
    cache.delete();
    assertNull(ResponseCache.getDefault());
  }

  /**
   * Make sure that statistics tracking are wired all the way through the
   * wrapper class. http://code.google.com/p/android/issues/detail?id=25418
   */
  @Test public void statisticsTracking() throws Exception {
    HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));

    URLConnection c1 = openUrl(server.getUrl("/"));

    InputStream inputStream = c1.getInputStream();
    assertEquals('A', inputStream.read());
    inputStream.close();
    assertEquals(1, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());

    URLConnection c2 = openUrl(server.getUrl("/"));
    assertEquals('A', c2.getInputStream().read());

    URLConnection c3 = openUrl(server.getUrl("/"));
    assertEquals('A', c3.getInputStream().read());
    assertEquals(3, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(2, cache.getHitCount());
  }

  // This mimics the Android HttpHandler, which is found in the com.squareup.okhttp package.
  private URLConnection openUrl(URL url) {
    ResponseCache responseCache = ResponseCache.getDefault();
    AndroidInternal.setResponseCache(client, responseCache);
    return client.open(url);
  }

  private void initializeCache(HttpResponseCache cache) {
    // Ensure the cache is initialized, otherwise various methods are no-ops.
    cache.size();
  }
}
