package okhttp3.doh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class DohCallTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final OkHttpClient bootstrapClient =
      new OkHttpClient.Builder().protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
          .build();
  private final Dns dns = buildLocalhost(bootstrapClient);

  private static final InetAddress localhost = address("localhost");

  @Before public void setUp() {
    server.setProtocols(bootstrapClient.protocols());
  }

  @After public void tearDown() {
  }

  @Test public void get() throws Exception {
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112"));

    List<InetAddress> result = dns.lookup("google.com");

    assertEquals(singletonList(address("157.240.1.18")), result);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/lookup?ct&dns=AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29tAAAcAAE",
        recordedRequest.getPath());
  }

  private MockResponse dnsResponse(String s) {
    return new MockResponse()
        .setBody(new Buffer().write(ByteString.decodeHex(s)))
        .addHeader("content-type", "application/dns-message")
        .addHeader("content-length", s.length() / 2);
  }

  DnsOverHttps buildLocalhost(OkHttpClient bootstrapClient) {
    BootstrapDns bootstrapDns = new BootstrapDns("localhost", localhost);
    HttpUrl urlPrefix = server.url("/lookup?ct&dns=");
    return new DnsOverHttps(bootstrapClient, urlPrefix.toString(), bootstrapDns, true);
  }

  private static InetAddress address(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // unlikely
      throw new RuntimeException(e);
    }
  }
}
