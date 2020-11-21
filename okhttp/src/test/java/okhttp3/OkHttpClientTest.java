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
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.SocketAddress;
import java.net.URI;
import java.security.KeyManagementException;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.proxy.NullProxySelector;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

public final class OkHttpClientTest {
  private static final ProxySelector DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault();
  private static final CookieHandler DEFAULT_COOKIE_HANDLER = CookieManager.getDefault();
  private static final ResponseCache DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault();

  @RegisterExtension public PlatformRule platform = new PlatformRule();
  @RegisterExtension public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private MockWebServer server;

  @BeforeEach
  public void setUp(MockWebServer server) throws Exception {
    this.server = server;
  }

  @AfterEach public void tearDown() throws Exception {
    ProxySelector.setDefault(DEFAULT_PROXY_SELECTOR);
    CookieManager.setDefault(DEFAULT_COOKIE_HANDLER);
    ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE);
  }

  @Test public void durationDefaults() {
    OkHttpClient client = clientTestRule.newClient();
    assertThat(client.callTimeoutMillis()).isEqualTo(0);
    assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.readTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.writeTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.pingIntervalMillis()).isEqualTo(0);
  }

  @Test public void webSocketDefaults() {
    OkHttpClient client = clientTestRule.newClient();
    assertThat(client.minWebSocketMessageToCompress()).isEqualTo(1024);
  }

  @Test public void timeoutValidRange() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.callTimeout(Duration.ofNanos(1));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.connectTimeout(Duration.ofNanos(1));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(Duration.ofNanos(1));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(Duration.ofNanos(1));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.callTimeout(Duration.ofDays(365));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.connectTimeout(Duration.ofDays(365));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(Duration.ofDays(365));
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(Duration.ofDays(365));
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Test public void clonedInterceptorsListsAreIndependent() throws Exception {
    Interceptor interceptor = chain -> chain.proceed(chain.request());
    OkHttpClient original = clientTestRule.newClient();
    original.newBuilder()
        .addInterceptor(interceptor)
        .addNetworkInterceptor(interceptor)
        .build();
    assertThat(original.interceptors().size()).isEqualTo(0);
    assertThat(original.networkInterceptors().size()).isEqualTo(0);
  }

  /**
   * When copying the client, stateful things like the connection pool are shared across all
   * clients.
   */
  @Test public void cloneSharesStatefulInstances() throws Exception {
    OkHttpClient client = clientTestRule.newClient();

    // Values should be non-null.
    OkHttpClient a = client.newBuilder().build();
    assertThat(a.dispatcher()).isNotNull();
    assertThat(a.connectionPool()).isNotNull();
    assertThat(a.sslSocketFactory()).isNotNull();
    assertThat(a.x509TrustManager()).isNotNull();

    // Multiple clients share the instances.
    OkHttpClient b = client.newBuilder().build();
    assertThat(b.dispatcher()).isSameAs(a.dispatcher());
    assertThat(b.connectionPool()).isSameAs(a.connectionPool());
    assertThat(b.sslSocketFactory()).isSameAs(a.sslSocketFactory());
    assertThat(b.x509TrustManager()).isSameAs(a.x509TrustManager());
  }

  @Test public void setProtocolsRejectsHttp10() throws Exception {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.protocols(asList(Protocol.HTTP_1_0, Protocol.HTTP_1_1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void certificatePinnerEquality() {
    OkHttpClient clientA = clientTestRule.newClient();
    OkHttpClient clientB = clientTestRule.newClient();
    assertThat(clientB.certificatePinner()).isEqualTo(clientA.certificatePinner());
  }

  @Test public void nullInterceptor() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.addInterceptor(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void nullNetworkInterceptor() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.addNetworkInterceptor(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void nullInterceptorInList() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    builder.interceptors().add(null);
    try {
      builder.build();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo("Null interceptor: [null]");
    }
  }

  @Test public void nullNetworkInterceptorInList() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    builder.networkInterceptors().add(null);
    try {
      builder.build();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo("Null network interceptor: [null]");
    }
  }

  @Test public void testH2PriorKnowledgeOkHttpClientConstructionFallback() {
    try {
      new OkHttpClient.Builder()
          .protocols(asList(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1));
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("protocols containing h2_prior_knowledge cannot use other protocols: "
            + "[h2_prior_knowledge, http/1.1]"));
    }
  }

  @Test public void testH2PriorKnowledgeOkHttpClientConstructionDuplicates() {
    try {
      new OkHttpClient.Builder()
          .protocols(asList(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.H2_PRIOR_KNOWLEDGE));
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("protocols containing h2_prior_knowledge cannot use other protocols: "
            + "[h2_prior_knowledge, h2_prior_knowledge]"));
    }
  }

  @Test public void testH2PriorKnowledgeOkHttpClientConstructionSuccess() {
    OkHttpClient okHttpClient = new OkHttpClient.Builder()
        .protocols(asList(Protocol.H2_PRIOR_KNOWLEDGE))
        .build();
    assertThat(okHttpClient.protocols().size()).isEqualTo(1);
    assertThat(okHttpClient.protocols().get(0)).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE);
  }

  @Test public void nullDefaultProxySelector() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    ProxySelector.setDefault(null);

    OkHttpClient client = clientTestRule.newClient();

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void sslSocketFactorySetAsSocketFactory() throws Exception {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.socketFactory(SSLSocketFactory.getDefault());
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void noSslSocketFactoryConfigured() throws Exception {
    OkHttpClient client = new OkHttpClient.Builder()
        .connectionSpecs(asList(ConnectionSpec.CLEARTEXT))
        .build();
    try {
      client.sslSocketFactory();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void nullHostileProtocolList() {
    List<Protocol> nullHostileProtocols = new AbstractList<Protocol>() {
      @Override public boolean contains(Object o) {
        if (o == null) throw new NullPointerException();
        return super.contains(o);
      }

      @Override public int indexOf(Object o) {
        if (o == null) throw new NullPointerException();
        return super.indexOf(o);
      }

      @Override public Protocol get(int index) {
        if (index != 0) throw new IndexOutOfBoundsException();
        return Protocol.HTTP_1_1;
      }

      @Override public int size() {
        return 1;
      }
    };

    OkHttpClient client = new OkHttpClient.Builder()
        .protocols(nullHostileProtocols)
        .build();
    assertEquals(asList(Protocol.HTTP_1_1), client.protocols());
  }

  @Test public void nullProtocolInList() {
    List<Protocol> protocols = new ArrayList<>();
    protocols.add(Protocol.HTTP_1_1);
    protocols.add(null);
    try {
      new OkHttpClient.Builder().protocols(protocols);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("protocols must not contain null");
    }
  }

  @Test public void spdy3IsRemovedFromProtocols() {
    List<Protocol> protocols = new ArrayList<>();
    protocols.add(Protocol.HTTP_1_1);
    protocols.add(Protocol.SPDY_3);
    OkHttpClient client = new OkHttpClient.Builder().protocols(protocols).build();
    assertThat(client.protocols()).containsExactly(Protocol.HTTP_1_1);
  }

  @Test public void testProxyDefaults() {
    OkHttpClient client = new OkHttpClient.Builder().build();
    assertThat(client.proxy()).isNull();
    assertThat(client.proxySelector()).isNotInstanceOf(NullProxySelector.class);

    client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build();
    assertThat(client.proxy()).isSameAs(Proxy.NO_PROXY);
    assertThat(client.proxySelector()).isInstanceOf(NullProxySelector.class);

    client = new OkHttpClient.Builder().proxySelector(new FakeProxySelector()).build();
    assertThat(client.proxy()).isNull();
    assertThat(client.proxySelector()).isInstanceOf(FakeProxySelector.class);
  }

  @Test
  public void sharesRouteDatabase() throws KeyManagementException {
    OkHttpClient client = new OkHttpClient.Builder().build();

    ProxySelector proxySelector = new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
        return emptyList();
      }

      @Override
      public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
      }
    };

    X509TrustManager trustManager = Platform.get().platformTrustManager();
    SSLContext sslContext = Platform.get().newSSLContext();
    sslContext.init(null, null, null);

    // new client, may share all same fields but likely different connection pool
    assertNotSame(client.getRouteDatabase(), new OkHttpClient.Builder().build().getRouteDatabase());

    // same client with no change affecting route db
    assertSame(client.getRouteDatabase(), client.newBuilder().build().getRouteDatabase());
    assertSame(client.getRouteDatabase(),
        client.newBuilder().callTimeout(Duration.ofSeconds(5)).build().getRouteDatabase());

    // logically different scope of client for route db
    assertNotSame(client.getRouteDatabase(),
        client.newBuilder().dns(hostname -> Collections.emptyList()).build().getRouteDatabase());
    assertNotSame(client.getRouteDatabase(), client.newBuilder()
        .proxyAuthenticator((route, response) -> null)
        .build()
        .getRouteDatabase());
    assertNotSame(client.getRouteDatabase(),
        client.newBuilder().protocols(singletonList(Protocol.HTTP_1_1)).build().getRouteDatabase());
    assertNotSame(client.getRouteDatabase(), client.newBuilder()
        .connectionSpecs(singletonList(ConnectionSpec.COMPATIBLE_TLS))
        .build()
        .getRouteDatabase());
    assertNotSame(client.getRouteDatabase(),
        client.newBuilder().proxySelector(proxySelector).build().getRouteDatabase());
    assertNotSame(client.getRouteDatabase(),
        client.newBuilder().proxy(Proxy.NO_PROXY).build().getRouteDatabase());
    assertNotSame(client.getRouteDatabase(), client.newBuilder()
        .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
        .build()
        .getRouteDatabase());
    assertNotSame(client.getRouteDatabase(),
        client.newBuilder().hostnameVerifier((s, sslSession) -> false).build().getRouteDatabase());
    assertNotSame(client.getRouteDatabase(), client.newBuilder()
        .certificatePinner(new CertificatePinner.Builder().build())
        .build()
        .getRouteDatabase());
  }

  @Test public void minWebSocketMessageToCompressNegative() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    try {
      builder.minWebSocketMessageToCompress(-1024);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage())
              .isEqualTo("minWebSocketMessageToCompress must be positive: -1024");
    }
  }
}
