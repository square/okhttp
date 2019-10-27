/*
 * Copyright (C) 2017 Square, Inc.
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ConnectionCoalescingTest {
  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client;

  private HeldCertificate rootCa;
  private HeldCertificate certificate;
  private FakeDns dns = new FakeDns();
  private HttpUrl url;
  private List<InetAddress> serverIps;

  @Before public void setUp() throws Exception {
    platform.assumeHttp2Support();

    rootCa = new HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(0)
        .commonName("root")
        .build();
    certificate = new HeldCertificate.Builder()
        .signedBy(rootCa)
        .serialNumber(2L)
        .commonName(server.getHostName())
        .addSubjectAlternativeName(server.getHostName())
        .addSubjectAlternativeName("san.com")
        .addSubjectAlternativeName("*.wildcard.com")
        .addSubjectAlternativeName("differentdns.com")
        .build();

    serverIps = Dns.SYSTEM.lookup(server.getHostName());

    dns.set(server.getHostName(), serverIps);
    dns.set("san.com", serverIps);
    dns.set("nonsan.com", serverIps);
    dns.set("www.wildcard.com", serverIps);
    dns.set("differentdns.com", Collections.emptyList());

    HandshakeCertificates handshakeCertificates = new HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCa.certificate())
        .build();

    client = clientTestRule.newClientBuilder()
        .dns(dns)
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .build();

    HandshakeCertificates serverHandshakeCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build();
    server.useHttps(serverHandshakeCertificates.sslSocketFactory(), false);

    url = server.url("/robots.txt");
  }

  /**
   * Test connecting to the main host then an alternative, although only subject alternative names
   * are used if present no special consideration of common name.
   */
  @Test public void commonThenAlternative() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /**
   * Test connecting to an alternative host then common name, although only subject alternative
   * names are used if present no special consideration of common name.
   */
  @Test public void alternativeThenCommon() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assert200Http2Response(execute(url), server.getHostName());

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /** Test a previously coalesced connection that's no longer healthy. */
  @Test public void staleCoalescedConnection() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    AtomicReference<Connection> connection = new AtomicReference<>();
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> {
          connection.set(chain.connection());
          return chain.proceed(chain.request());
        })
        .build();
    dns.set("san.com", Dns.SYSTEM.lookup(server.getHostName()).subList(0, 1));

    assert200Http2Response(execute(url), server.getHostName());

    // Simulate a stale connection in the pool.
    connection.get().socket().close();

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /**
   * This is an extraordinary test case. Here's what it's trying to simulate.
   * - 2 requests happen concurrently to a host that can be coalesced onto a single connection.
   * - Both request discover no existing connection. They both make a connection.
   * - The first request "wins the race".
   * - The second request discovers it "lost the race" and closes the connection it just opened.
   * - The second request uses the coalesced connection from request1.
   * - The coalesced connection is violently closed after servicing the first request.
   * - The second request discovers the coalesced connection is unhealthy just after acquiring it.
   */
  @Test public void coalescedConnectionDestroyedAfterAcquire() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    dns.set("san.com", Dns.SYSTEM.lookup(server.getHostName()).subList(0, 1));
    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch latch3 = new CountDownLatch(1);
    CountDownLatch latch4 = new CountDownLatch(1);
    EventListener listener1 = new EventListener() {
      @Override public void connectStart(Call call, InetSocketAddress inetSocketAddress,
          Proxy proxy) {
        try {
          // Wait for request2 to guarantee we make 2 separate connections to the server.
          latch1.await();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }

      @Override public void connectionAcquired(Call call, Connection connection) {
        // We have the connection and it's in the pool. Let request2 proceed to make a connection.
        latch2.countDown();
      }
    };

    EventListener request2Listener = new EventListener() {
      @Override public void connectStart(Call call, InetSocketAddress inetSocketAddress,
          Proxy proxy) {
        // Let request1 proceed to make a connection.
        latch1.countDown();
        try {
          // Wait until request1 makes the connection and puts it in the connection pool.
          latch2.await();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }

      @Override public void connectionAcquired(Call call, Connection connection) {
        // We obtained the coalesced connection. Let request1 violently destroy it.
        latch3.countDown();
        try {
          latch4.await();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
    };

    // Get a reference to the connection so we can violently destroy it.
    AtomicReference<Connection> connection = new AtomicReference<>();
    OkHttpClient client1 = client.newBuilder()
        .addNetworkInterceptor(chain -> {
          connection.set(chain.connection());
          return chain.proceed(chain.request());
        })
        .eventListenerFactory(clientTestRule.wrap(listener1))
        .build();

    Request request = new Request.Builder().url(sanUrl).build();
    Call call1 = client1.newCall(request);
    call1.enqueue(new Callback() {
      @Override public void onResponse(Call call, Response response) throws IOException {
        try {
          // Wait until request2 acquires the connection before we destroy it violently.
          latch3.await();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
        assert200Http2Response(response, "san.com");
        connection.get().socket().close();
        latch4.countDown();
      }

      @Override public void onFailure(Call call, IOException e) {
        fail();
      }
    });

    OkHttpClient client2 = client.newBuilder()
        .eventListenerFactory(clientTestRule.wrap(request2Listener))
        .build();
    Call call2 = client2.newCall(request);
    Response response = call2.execute();

    assert200Http2Response(response, "san.com");
  }

  /** If the existing connection matches a SAN but not a match for DNS then skip. */
  @Test public void skipsWhenDnsDontMatch() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl differentDnsUrl = url.newBuilder().host("differentdns.com").build();
    try {
      execute(differentDnsUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException expected) {
    }
  }

  /** Not in the certificate SAN. */
  @Test public void skipsWhenNotSubjectAltName() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl nonsanUrl = url.newBuilder().host("nonsan.com").build();

    try {
      execute(nonsanUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException expected) {
    }
  }

  /** Can still coalesce when pinning is used if pins match. */
  @Test public void coalescesWhenCertificatePinsMatch() throws Exception {
    CertificatePinner pinner = new CertificatePinner.Builder()
        .add("san.com", CertificatePinner.pin(certificate.certificate()))
        .build();
    client = client.newBuilder().certificatePinner(pinner).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /** Certificate pinning used and not a match will avoid coalescing and try to connect. */
  @Test public void skipsWhenCertificatePinningFails() throws Exception {
    CertificatePinner pinner = new CertificatePinner.Builder()
        .add("san.com", "sha1/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
        .build();
    client = client.newBuilder().certificatePinner(pinner).build();

    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    try {
      execute(sanUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException expected) {
    }
  }

  /**
   * Skips coalescing when hostname verifier is overridden since the intention of the hostname
   * verification is a black box.
   */
  @Test public void skipsWhenHostnameVerifierUsed() throws Exception {
    HostnameVerifier verifier = (name, session) -> true;
    client = client.newBuilder().hostnameVerifier(verifier).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(2);
  }

  /**
   * Check we would use an existing connection to a later DNS result instead of connecting to the
   * first DNS result for the first time.
   */
  @Test public void prefersExistingCompatible() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    AtomicInteger connectCount = new AtomicInteger();
    EventListener listener = new EventListener() {
      @Override public void connectStart(
          Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
        connectCount.getAndIncrement();
      }
    };
    client = client.newBuilder()
        .eventListenerFactory(clientTestRule.wrap(listener))
        .build();

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    dns.set("san.com",
        asList(InetAddress.getByAddress("san.com", new byte[] {0, 0, 0, 0}),
            serverIps.get(0)));
    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
    assertThat(connectCount.get()).isEqualTo(1);
  }

  /** Check that wildcard SANs are supported. */
  @Test public void commonThenWildcard() throws Exception {

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("www.wildcard.com").build();
    assert200Http2Response(execute(sanUrl), "www.wildcard.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /** Network interceptors check for changes to target. */
  @Test public void worksWithNetworkInterceptors() throws Exception {
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> chain.proceed(chain.request()))
        .build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  private Response execute(HttpUrl url) throws IOException {
    return client.newCall(new Request.Builder().url(url).build()).execute();
  }

  private void assert200Http2Response(Response response, String expectedHost) {
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.request().url().host()).isEqualTo(expectedHost);
    assertThat(response.protocol()).isEqualTo(Protocol.HTTP_2);
    response.body().close();
  }
}
