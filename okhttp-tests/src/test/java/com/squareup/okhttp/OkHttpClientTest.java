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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.RecordingAuthenticator;
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.internal.http.RecordingProxySelector;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class OkHttpClientTest {
  private static final ProxySelector DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault();
  private static final CookieHandler DEFAULT_COOKIE_HANDLER = CookieManager.getDefault();
  private static final ResponseCache DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault();
  private static final Authenticator DEFAULT_AUTHENTICATOR = null; // No Authenticator.getDefault().

  @After public void tearDown() throws Exception {
    ProxySelector.setDefault(DEFAULT_PROXY_SELECTOR);
    CookieManager.setDefault(DEFAULT_COOKIE_HANDLER);
    ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE);
    Authenticator.setDefault(DEFAULT_AUTHENTICATOR);
  }

  @Test public void timeoutDefaults() {
    OkHttpClient client = new OkHttpClient();
    assertEquals(10_000, client.getConnectTimeout());
    assertEquals(10_000, client.getReadTimeout());
    assertEquals(10_000, client.getWriteTimeout());
    assertEquals(10_000, client.getSocketTimeout());
  }

  @Test public void timeoutValidRange() {
    OkHttpClient client = new OkHttpClient();
    try {
      client.setConnectTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      client.setWriteTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      client.setReadTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      client.setConnectTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      client.setWriteTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      client.setReadTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
  }

  /** Confirm that {@code copyWithDefaults} gets expected constant values. */
  @Test public void copyWithDefaultsWhenDefaultIsAConstant() throws Exception {
    OkHttpClient client = new OkHttpClient().copyWithDefaults();
    assertNull(client.internalCache());
    assertEquals(10_000, client.getConnectTimeout());
    assertEquals(10_000, client.getReadTimeout());
    assertEquals(10_000, client.getWriteTimeout());
    assertEquals(10_000, client.getSocketTimeout());
    assertTrue(client.getFollowSslRedirects());
    assertNull(client.getProxy());
    assertEquals(Arrays.asList(Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1),
        client.getProtocols());
  }

  /**
   * Confirm that {@code copyWithDefaults} gets some default implementations
   * from the core library.
   */
  @Test public void copyWithDefaultsWhenDefaultIsGlobal() throws Exception {
    ProxySelector proxySelector = new RecordingProxySelector();
    CookieManager cookieManager = new CookieManager();
    Authenticator authenticator = new RecordingAuthenticator();
    SocketFactory socketFactory = SocketFactory.getDefault(); // Global isn't configurable.
    OkHostnameVerifier hostnameVerifier = OkHostnameVerifier.INSTANCE; // Global isn't configurable.
    CertificatePinner certificatePinner = CertificatePinner.DEFAULT; // Global isn't configurable.

    CookieManager.setDefault(cookieManager);
    ProxySelector.setDefault(proxySelector);
    Authenticator.setDefault(authenticator);

    OkHttpClient client = new OkHttpClient().copyWithDefaults();

    assertSame(proxySelector, client.getProxySelector());
    assertSame(cookieManager, client.getCookieHandler());
    assertSame(AuthenticatorAdapter.INSTANCE, client.getAuthenticator());
    assertSame(socketFactory, client.getSocketFactory());
    assertSame(hostnameVerifier, client.getHostnameVerifier());
    assertSame(certificatePinner, client.getCertificatePinner());
  }

  /** There is no default cache. */
  @Test public void copyWithDefaultsCacheIsNull() throws Exception {
    OkHttpClient client = new OkHttpClient().copyWithDefaults();
    assertNull(client.getCache());
  }

  @Test public void copyWithDefaultsDoesNotHonorGlobalResponseCache() {
    ResponseCache.setDefault(new ResponseCache() {
      @Override public CacheResponse get(URI uri, String requestMethod,
          Map<String, List<String>> requestHeaders) throws IOException {
        throw new AssertionError();
      }

      @Override public CacheRequest put(URI uri, URLConnection connection) {
        throw new AssertionError();
      }
    });

    OkHttpClient client = new OkHttpClient().copyWithDefaults();
    assertNull(client.internalCache());
  }

  @Test public void clonedInterceptorsListsAreIndependent() throws Exception {
    OkHttpClient original = new OkHttpClient();
    OkHttpClient clone = original.clone();
    clone.interceptors().add(null);
    clone.networkInterceptors().add(null);
    assertEquals(0, original.interceptors().size());
    assertEquals(0, original.networkInterceptors().size());
  }

  /**
   * When copying the client, stateful things like the connection pool are
   * shared across all clients.
   */
  @Test public void cloneSharesStatefulInstances() throws Exception {
    OkHttpClient client = new OkHttpClient();

    // Values should be non-null.
    OkHttpClient a = client.clone().copyWithDefaults();
    assertNotNull(a.routeDatabase());
    assertNotNull(a.getDispatcher());
    assertNotNull(a.getConnectionPool());
    assertNotNull(a.getSslSocketFactory());

    // Multiple clients share the instances.
    OkHttpClient b = client.clone().copyWithDefaults();
    assertSame(a.routeDatabase(), b.routeDatabase());
    assertSame(a.getDispatcher(), b.getDispatcher());
    assertSame(a.getConnectionPool(), b.getConnectionPool());
    assertSame(a.getSslSocketFactory(), b.getSslSocketFactory());
  }

  @Test public void setProtocolsRejectsHttp10() throws Exception {
    OkHttpClient client = new OkHttpClient();
    try {
      client.setProtocols(Arrays.asList(Protocol.HTTP_1_0, Protocol.HTTP_1_1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
