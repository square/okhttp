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
package okhttp3;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class OkHttpClientTest {
  private static final ProxySelector DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault();
  private static final CookieHandler DEFAULT_COOKIE_HANDLER = CookieManager.getDefault();
  private static final ResponseCache DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault();

  @After public void tearDown() throws Exception {
    ProxySelector.setDefault(DEFAULT_PROXY_SELECTOR);
    CookieManager.setDefault(DEFAULT_COOKIE_HANDLER);
    ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE);
  }

  @Test public void timeoutDefaults() {
    OkHttpClient client = defaultClient();
    assertEquals(10_000, client.connectTimeoutMillis());
    assertEquals(10_000, client.readTimeoutMillis());
    assertEquals(10_000, client.writeTimeoutMillis());
    assertEquals(10_000, client.requestDeadlineMillis());
  }

  @Test public void timeoutValidRange() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.connectTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.requestDeadline(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.connectTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.requestDeadline(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Test public void clonedInterceptorsListsAreIndependent() throws Exception {
    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request());
      }
    };
    OkHttpClient original = defaultClient();
    original.newBuilder()
        .addInterceptor(interceptor)
        .addNetworkInterceptor(interceptor)
        .build();
    assertEquals(0, original.interceptors().size());
    assertEquals(0, original.networkInterceptors().size());
  }

  /**
   * When copying the client, stateful things like the connection pool are shared across all
   * clients.
   */
  @Test public void cloneSharesStatefulInstances() throws Exception {
    OkHttpClient client = defaultClient();

    // Values should be non-null.
    OkHttpClient a = client.newBuilder().build();
    assertNotNull(a.dispatcher());
    assertNotNull(a.connectionPool());
    assertNotNull(a.sslSocketFactory());

    // Multiple clients share the instances.
    OkHttpClient b = client.newBuilder().build();
    assertSame(a.dispatcher(), b.dispatcher());
    assertSame(a.connectionPool(), b.connectionPool());
    assertSame(a.sslSocketFactory(), b.sslSocketFactory());
  }

  @Test public void setProtocolsRejectsHttp10() throws Exception {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.protocols(Arrays.asList(Protocol.HTTP_1_0, Protocol.HTTP_1_1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
