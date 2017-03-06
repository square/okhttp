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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import okhttp3.internal.tls.HeldCertificate;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class ConnectionCoalescingTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private OkHttpClient client;

  private HeldCertificate rootCa;
  private HeldCertificate certificate;
  private FakeDns dns = new FakeDns();
  private HttpUrl url;
  private List<InetAddress> serverIps;

  @Before public void setUp() throws Exception {
    rootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();
    certificate = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .serialNumber("2")
        .commonName(server.getHostName())
        .subjectAlternativeName(server.getHostName())
        .subjectAlternativeName("san.com")
        .subjectAlternativeName("*.wildcard.com")
        .subjectAlternativeName("differentdns.com")
        .build();

    serverIps = Dns.SYSTEM.lookup(server.getHostName());

    dns.set(server.getHostName(), serverIps);
    dns.set("san.com", serverIps);
    dns.set("nonsan.com", serverIps);
    dns.set("www.wildcard.com", serverIps);
    dns.set("differentdns.com", Collections.<InetAddress>emptyList());

    SslClient sslClient = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build();

    client = new OkHttpClient.Builder().dns(dns)
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .build();

    SslClient serverSslClient = new SslClient.Builder()
        .certificateChain(certificate, rootCa)
        .build();
    server.useHttps(serverSslClient.socketFactory, false);

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

    assertEquals(1, client.connectionPool().connectionCount());
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

    assertEquals(1, client.connectionPool().connectionCount());
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
        .add("san.com", "sha1/" + CertificatePinner.sha1(certificate.certificate).base64())
        .build();
    client = client.newBuilder().certificatePinner(pinner).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
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
    HostnameVerifier verifier = new HostnameVerifier() {
      @Override public boolean verify(String s, SSLSession sslSession) {
        return true;
      }
    };
    client = client.newBuilder().hostnameVerifier(verifier).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(2, client.connectionPool().connectionCount());
  }

  /**
   * Check we would use an existing connection to a later DNS result instead of connecting to the
   * first DNS result for the first time.
   */
  @Test public void prefersExistingCompatible() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    dns.set("san.com",
        Arrays.asList(InetAddress.getByAddress("san.com", new byte[] {0, 0, 0, 0}),
            serverIps.get(0)));
    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  /** Check that wildcard SANs are supported. */
  @Test public void commonThenWildcard() throws Exception {

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("www.wildcard.com").build();
    assert200Http2Response(execute(sanUrl), "www.wildcard.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  /** Network interceptors check for changes to target. */
  @Test public void worksWithNetworkInterceptors() throws Exception {
    client = client.newBuilder().addNetworkInterceptor(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request());
      }
    }).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  /** Run against public external sites, doesn't run by default. */
  @Ignore
  @Test public void coalescesConnectionsToRealSites() throws IOException {
    client = new OkHttpClient();

    assert200Http2Response(execute("https://graph.facebook.com/robots.txt"), "graph.facebook.com");
    assert200Http2Response(execute("https://www.facebook.com/robots.txt"), "m.facebook.com");
    assert200Http2Response(execute("https://fb.com/robots.txt"), "m.facebook.com");
    assert200Http2Response(execute("https://messenger.com/robots.txt"), "messenger.com");
    assert200Http2Response(execute("https://m.facebook.com/robots.txt"), "m.facebook.com");

    assertEquals(3, client.connectionPool().connectionCount());
  }

  private Response execute(String url) throws IOException {
    return execute(HttpUrl.parse(url));
  }

  private Response execute(HttpUrl url) throws IOException {
    return client.newCall(new Request.Builder().url(url).build()).execute();
  }

  private void assert200Http2Response(Response response, String expectedHost) {
    assertEquals(200, response.code());
    assertEquals(expectedHost, response.request().url().host());
    assertEquals(Protocol.HTTP_2, response.protocol());
  }
}
