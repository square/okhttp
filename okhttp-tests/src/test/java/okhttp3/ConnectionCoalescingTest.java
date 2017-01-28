package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.SocketFactory;
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
  private HttpUrl robotsUrl;

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
        .subjectAlternativeName("test1.com")
        .subjectAlternativeName("test2.com")
        .subjectAlternativeName("*.test3.com")
        .build();

    List<InetAddress> serverIps = Dns.SYSTEM.lookup(server.getHostName());

    dnsResults.put("test1.com", serverIps);
    dnsResults.put("test2.com", serverIps);
    dnsResults.put("www.test3.com", serverIps);
    dnsResults.put("google.com", serverIps);

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

    robotsUrl = server.url("/robots.txt");
  }

  @Test
  public void commonThenAlternative() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    HttpUrl test1Url = robotsUrl.newBuilder().host("test1.com").build();

    assert200Http2Response(execute(robotsUrl), server.getHostName());
    assert200Http2Response(execute(test1Url), "test1.com");

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void alternativeThenCommon() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    HttpUrl test1Url = robotsUrl.newBuilder().host("test1.com").build();

    assert200Http2Response(execute(test1Url), "test1.com");
    assert200Http2Response(execute(robotsUrl), server.getHostName());

    assertEquals(1, client.connectionPool().connectionCount());
  }

  @Test
  public void skipsWhenDnsDontMatch() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    // TODO how to use a fake host but redirect to same webserver
    HttpUrl test1Url = robotsUrl.newBuilder().host("google.com").build();

    dnsResults.remove("google.com");

    assert200Http2Response(execute(test1Url), "google.com");
    assert200Http2Response(execute(robotsUrl), server.getHostName());

    assertEquals(2, client.connectionPool().connectionCount());
  }

  @Test
  public void skipsWhenNotSubjectAltName() {
    fail();
  }

  @Test
  public void prefersExistingCompatible() {
    fail();
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
