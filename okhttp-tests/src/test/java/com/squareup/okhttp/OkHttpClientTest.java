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
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.util.Arrays;
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

  /** Confirm that {@code copyWithDefaults} gets expected constant values. */
  @Test public void copyWithDefaultsWhenDefaultIsAConstant() throws Exception {
    OkHttpClient client = new OkHttpClient().copyWithDefaults();
    assertNull(client.internalCache());
    assertEquals(0, client.getConnectTimeout());
    assertEquals(0, client.getReadTimeout());
    assertEquals(0, client.getWriteTimeout());
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

  @Test public void copyWithDefaultsDoesNotHonorGlobalResponseCache() throws Exception {
    ResponseCache responseCache = new AbstractResponseCache();
    ResponseCache.setDefault(responseCache);

    OkHttpClient client = new OkHttpClient().copyWithDefaults();
    assertNull(client.internalCache());
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

  /** We don't want to run user code inside of HttpEngine, etc. */
  @Test public void copyWithDefaultsDoesNotReturnSubclass() throws Exception {
    OkHttpClient subclass = new OkHttpClient() {};
    OkHttpClient copy = subclass.copyWithDefaults();
    assertEquals(OkHttpClient.class, copy.getClass());
  }

  @Test public void cloneReturnsSubclass() throws Exception {
    OkHttpClient subclass = new OkHttpClient() {};
    OkHttpClient clone = subclass.clone();
    assertEquals(subclass.getClass(), clone.getClass());
  }

  /** Exercise a synchronous mocking case. */
  @Test public void mock() throws Exception {
    final Request request = new Request.Builder()
        .url("http://example.com/")
        .build();
    final Response response = new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .code(200)
        .message("Alright")
        .build();

    OkHttpClient mockClient = new OkHttpClient() {
      @Override public Call newCall(Request request) {
        return new Call(this, request) {
          @Override public Response execute() throws IOException {
            return response;
          }
          @Override public void enqueue(Callback responseCallback) {
          }
          @Override public void cancel() {
          }
        };
      }
    };

    Response actualResponse = mockClient.newCall(request).execute();
    assertSame(response, actualResponse);
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
