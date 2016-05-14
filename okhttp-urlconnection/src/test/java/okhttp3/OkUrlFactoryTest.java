package okhttp3;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import okhttp3.internal.URLFilter;
import okhttp3.internal.http.OkHeaders;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.BufferedSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static okio.Okio.buffer;
import static okio.Okio.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class OkUrlFactoryTest {
  @Rule public MockWebServer server = new MockWebServer();
  @Rule public InMemoryFileSystem fileSystem = new InMemoryFileSystem();

  private OkUrlFactory factory;
  private Cache cache;

  @Before public void setUp() throws IOException {
    cache = new Cache(new File("/cache/"), 10 * 1024 * 1024, fileSystem);
    OkHttpClient client = new OkHttpClient.Builder()
        .cache(cache)
        .build();
    factory = new OkUrlFactory(client);
  }

  @After public void tearDown() throws IOException {
    cache.delete();
  }

  /**
   * Response code 407 should only come from proxy servers. Android's client throws if it is sent by
   * an origin server.
   */
  @Test public void originServerSends407() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(407));

    HttpURLConnection conn = factory.open(server.url("/").url());
    try {
      conn.getResponseCode();
      fail();
    } catch (IOException ignored) {
    }
  }

  @Test public void networkResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection = factory.open(server.url("/").url());
    assertResponseHeader(connection, "NETWORK 200");
    assertResponseBody(connection, "Isla Sorna");
  }

  @Test public void networkFailureResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));

    HttpURLConnection connection = factory.open(server.url("/").url());
    assertResponseHeader(connection, "NETWORK 404");
    connection.getErrorStream().close();
  }

  @Test public void conditionalCacheHitResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setResponseCode(304));

    HttpURLConnection connection1 = factory.open(server.url("/").url());
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.url("/").url());
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 304");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void conditionalCacheMissResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection1 = factory.open(server.url("/").url());
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.url("/").url());
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 200");
    assertResponseBody(connection2, "Isla Sorna");
  }

  @Test public void cacheResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Expires: " + formatDate(2, TimeUnit.HOURS))
        .setBody("Isla Nublar"));

    HttpURLConnection connection1 = factory.open(server.url("/").url());
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.url("/").url());
    assertResponseHeader(connection2, "CACHE 200");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void noneResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Nublar"));

    HttpURLConnection connection1 = factory.open(server.url("/").url());
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.url("/").url());
    connection2.setRequestProperty("Cache-Control", "only-if-cached");
    assertResponseHeader(connection2, "NONE");
  }

  @Test
  public void setInstanceFollowRedirectsFalse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpURLConnection connection = factory.open(server.url("/a").url());
    connection.setInstanceFollowRedirects(false);
    assertResponseBody(connection, "A");
    assertResponseCode(connection, 302);
  }

  @Test
  public void testURLFilter() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("B"));
    final URL blockedURL = server.url("/a").url();
    factory.setUrlFilter(new URLFilter() {
      @Override
      public void checkURLPermitted(URL url) throws IOException {
        if (blockedURL.equals(url)) {
          throw new IOException("Blocked");
        }
      }
    });
    try {
      HttpURLConnection connection = factory.open(server.url("/a").url());
      connection.getInputStream();
      fail("Connection was successful");
    } catch (IOException e) {
      assertEquals("Blocked", e.getMessage());
    }
    HttpURLConnection connection = factory.open(server.url("/b").url());
    assertResponseBody(connection, "B");
  }

  @Test
  public void testURLFilterRedirect() throws Exception {
    MockWebServer cleartextServer = new MockWebServer();
    cleartextServer.enqueue(new MockResponse()
        .setBody("Blocked!"));
    final URL blockedURL = cleartextServer.url("/").url();

    SslClient contextBuilder = SslClient.localhost();
    server.useHttps(contextBuilder.socketFactory, false);
    factory.setClient(factory.client().newBuilder()
        .sslSocketFactory(contextBuilder.socketFactory, contextBuilder.trustManager)
        .followSslRedirects(true)
        .build());
    factory.setUrlFilter(new URLFilter() {
      @Override
      public void checkURLPermitted(URL url) throws IOException {
        if (blockedURL.equals(url)) {
          throw new IOException("Blocked");
        }
      }
    });

    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + blockedURL)
        .setBody("This page has moved"));
    URL destination = server.url("/").url();
    try {
      HttpsURLConnection httpsConnection = (HttpsURLConnection) factory.open(destination);
      httpsConnection.getInputStream();
      fail("Connection was successful");
    } catch (IOException e) {
      assertEquals("Blocked", e.getMessage());
    }
  }

  private void assertResponseBody(HttpURLConnection connection, String expected) throws Exception {
    BufferedSource source = buffer(source(connection.getInputStream()));
    String actual = source.readString(US_ASCII);
    source.close();
    assertEquals(expected, actual);
  }

  private void assertResponseHeader(HttpURLConnection connection, String expected) {
    assertEquals(expected, connection.getHeaderField(OkHeaders.RESPONSE_SOURCE));
  }

  private void assertResponseCode(HttpURLConnection connection, int expected) throws IOException {
    assertEquals(expected, connection.getResponseCode());
  }

  private static String formatDate(long delta, TimeUnit timeUnit) {
    return formatDate(new Date(System.currentTimeMillis() + timeUnit.toMillis(delta)));
  }

  private static String formatDate(Date date) {
    DateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    rfc1123.setTimeZone(TimeZone.getTimeZone("GMT"));
    return rfc1123.format(date);
  }
}
