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
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public final class SocksProxyTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private final SocksProxy socksProxy = new SocksProxy();

  @Before public void setUp() throws Exception {
    socksProxy.play();
  }

  @After public void tearDown() throws Exception {
    socksProxy.shutdown();
  }

  @Test public void proxy() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    OkHttpClient client = clientTestRule.newClientBuilder()
        .proxy(socksProxy.proxy())
        .build();

    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertThat(response1.body().string()).isEqualTo("abc");

    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("def");

    // The HTTP calls should share a single connection.
    assertThat(socksProxy.connectionCount()).isEqualTo(1);
  }

  @Test public void proxySelector() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    ProxySelector proxySelector = new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        return Collections.singletonList(socksProxy.proxy());
      }

      @Override public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        throw new AssertionError();
      }
    };

    OkHttpClient client = clientTestRule.newClientBuilder()
        .proxySelector(proxySelector)
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("abc");

    assertThat(socksProxy.connectionCount()).isEqualTo(1);
  }

  @Test public void checkRemoteDNSResolve() throws Exception {
    // This testcase will fail if the target is resolved locally instead of through the proxy.
    server.enqueue(new MockResponse().setBody("abc"));

    OkHttpClient client = clientTestRule.newClientBuilder()
        .proxy(socksProxy.proxy())
        .build();

    HttpUrl url = server.url("/")
        .newBuilder()
        .host(SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS)
        .build();

    Request request = new Request.Builder().url(url).build();
    Response response1 = client.newCall(request).execute();
    assertThat(response1.body().string()).isEqualTo("abc");

    assertThat(socksProxy.connectionCount()).isEqualTo(1);
  }
}
