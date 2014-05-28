package com.squareup.okhttp;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static okio.Okio.buffer;
import static okio.Okio.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class OkUrlFactoryTest {
  @Rule public MockWebServerRule serverRule = new MockWebServerRule();
  @Rule public TemporaryFolder cacheFolder = new TemporaryFolder();

  private MockWebServer server;
  private OkUrlFactory factory;

  @Before public void setUp() throws IOException {
    server = serverRule.get();

    OkHttpClient client = new OkHttpClient();
    client.setCache(new Cache(cacheFolder.getRoot(), 10 * 1024 * 1024));
    factory = new OkUrlFactory(client);
  }

  /**
   * Response code 407 should only come from proxy servers. Android's client
   * throws if it is sent by an origin server.
   */
  @Test public void originServerSends407() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(407));

    HttpURLConnection conn = factory.open(server.getUrl("/"));
    try {
      conn.getResponseCode();
      fail();
    } catch (IOException ignored) {
    }
  }

  @Test public void networkResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection = factory.open(server.getUrl("/"));
    assertResponseHeader(connection, "NETWORK 200");
    assertResponseBody(connection, "Isla Sorna");
  }

  @Test public void networkFailureResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));

    HttpURLConnection connection = factory.open(server.getUrl("/"));
    assertResponseHeader(connection, "NETWORK 404");
  }

  @Test public void conditionalCacheHitResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setResponseCode(304));

    HttpURLConnection connection1 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 304");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void conditionalCacheMissResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection1 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 200");
    assertResponseBody(connection2, "Isla Sorna");
  }

  @Test public void cacheResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Expires: " + formatDate(2, TimeUnit.HOURS))
        .setBody("Isla Nublar"));

    HttpURLConnection connection1 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection2, "CACHE 200");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void noneResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Nublar"));

    HttpURLConnection connection1 = factory.open(server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = factory.open(server.getUrl("/"));
    connection2.setRequestProperty("Cache-Control", "only-if-cached");
    assertResponseHeader(connection2, "NONE");
  }

  private void assertResponseBody(HttpURLConnection connection, String expected) throws Exception {
    String actual = buffer(source(connection.getInputStream())).readString(US_ASCII);
    assertEquals(expected, actual);
  }

  private void assertResponseHeader(HttpURLConnection connection, String expected) {
    assertEquals(expected, connection.getHeaderField("OkHttp-Response-Source"));
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
