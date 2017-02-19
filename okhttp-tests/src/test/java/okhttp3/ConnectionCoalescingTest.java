package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

// https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
// https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/
// Used when both IP + subjectAlternativeName match
public class ConnectionCoalescingTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private OkHttpClient client;

  private HeldCertificate rootCa;
  private HeldCertificate certificate;
  private Map<String, List<InetAddress>> dnsResults = new LinkedHashMap<>();
  private HttpUrl url;
  private List<InetAddress> serverIps;

  @Before
  public void initialise() throws GeneralSecurityException, IOException {
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

    dnsResults.put(server.getHostName(), serverIps);
    dnsResults.put("san.com", serverIps);
    dnsResults.put("nonsan.com", serverIps);
    dnsResults.put("www.wildcard.com", serverIps);
    dnsResults.put("differentdns.com", Collections.<InetAddress>emptyList());

    SslClient sslClient = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build();

    Dns dns = new Dns() {
      @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> testResults = dnsResults.get(hostname);

        if (testResults == null) {
          testResults = Dns.SYSTEM.lookup(hostname);
        }

        return testResults;
      }
    };

    client = new OkHttpClient.Builder().dns(dns)
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .build();

    SslClient serverSslClient = new SslClient.Builder()
        .certificateChain(certificate, rootCa)
        .build();
    server.useHttps(serverSslClient.socketFactory, false);

    url = server.url("/robots.txt");
  }

  @Test
  public void commonThenAlternative() throws IOException {
    // test connecting to the main host then an alternative,
    // although only subject alternative names are used if present
    // no special consideration of common name

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void alternativeThenCommon() throws IOException {
    // test connecting to an alternative host then common name,
    // although only subject alternative names are used if present
    // no special consideration of common name

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    assert200Http2Response(execute(sanUrl), "san.com");

    assert200Http2Response(execute(url), server.getHostName());

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void skipsWhenDnsDontMatch() throws IOException {
    // if the existing connection matches a SAN
    // but not a match for DNS then skip

    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl differentdnsUrl = url.newBuilder().host("differentdns.com").build();

    try {
      execute(differentdnsUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException se) {
      // expected
    }
  }

  @Test
  public void skipsWhenNotSubjectAltName() throws IOException {
    // not in the certificate SAN

    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl nonsanUrl = url.newBuilder().host("nonsan.com").build();

    try {
      execute(nonsanUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException se) {
      // expected
    }
  }

  @Test
  public void coalescesWhenCertificatePinsMatch() throws IOException {
    // can still coalesce when pinning is used if pins match

    CertificatePinner pinner = new CertificatePinner.Builder().add("san.com",
        "sha1/" + CertificatePinner.sha1(certificate.certificate).base64()).build();
    client = client.newBuilder().certificatePinner(pinner).build();

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void skipsWhenCertificatePinningFails() throws IOException {
    // certificate pinning used and not a match
    // will avoid coalescing and try to connect

    CertificatePinner pinner = new CertificatePinner.Builder().add("san.com",
        "sha1/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=").build();
    client = client.newBuilder().certificatePinner(pinner).build();

    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();

    try {
      execute(sanUrl);
      fail("expected a failed attempt to connect");
    } catch (IOException se) {
      // expected
    }
  }

  @Test
  public void skipsWhenHostnameVerifierUsed() throws IOException {
    // skips coalescing when hostname verifier is overriden
    // since the intention of the hostname verification is a blackbox

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

  @Test
  public void prefersExistingCompatible() throws IOException {
    // check we would use an existing connection to a
    // later DNS result instead of connecting to the first
    // DNS result for the first time

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("san.com").build();
    dnsResults.put("san.com",
        Arrays.asList(InetAddress.getByAddress("san.com", new byte[] {0, 0, 0, 0}),
            serverIps.get(0)));
    assert200Http2Response(execute(sanUrl), "san.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void commonThenWildcard() throws IOException {
    // check that wildcard SANs are supported

    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("www.wildcard.com").build();
    assert200Http2Response(execute(sanUrl), "www.wildcard.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void worksWithNetworkInterceptors() throws IOException {
    // network interceptors check for changes to target

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


  /*
   * Run against public external sites, doesn't run by default.
   */
  @Test @Ignore
  public void coalescesConnectionsToRealSites() throws IOException {
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

  private void assert200Http2Response(Response twitterResponse, String expectedHost) {
    assertEquals(200, twitterResponse.code());
    assertEquals(expectedHost, twitterResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, twitterResponse.protocol());
  }
}
