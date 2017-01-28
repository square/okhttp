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
import okhttp3.internal.tls.HeldCertificate;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
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
    //

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
  public void prefersExistingCompatible() throws IOException {
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
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    assert200Http2Response(execute(url), server.getHostName());

    HttpUrl sanUrl = url.newBuilder().host("www.wildcard.com").build();
    assert200Http2Response(execute(sanUrl), "www.wildcard.com");

    assertEquals(1, client.connectionPool().connectionCount());
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
