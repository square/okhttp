package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Http2Test {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    public boolean verify(String hostname, SSLSession session) {
      return true;
    }
  };

  private static final SSLContext sslContext;
  static {
    try {
      sslContext = new SslContextBuilder(InetAddress.getLocalHost().getHostName()).build();
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }
  private final MockWebServer server = new MockWebServer();
  private final String hostName = server.getHostName();
  private final OkHttpClient client = new OkHttpClient();

  @Before public void setUp() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
    server.shutdown();
  }

  @Test public void testHttp2() throws Exception {
    MockResponse response = new MockResponse().setBody("ABCDE").setStatus("HTTP/1.1 200 Sweet");
    server.enqueue(response);
    server.play();

    client.setTransports(Arrays.asList("HTTP-draft-04/2.0", "http/1.1"));

    HttpURLConnection connection = client.open(server.getUrl("/foo"));
    assertContent("ABCDE", connection, Integer.MAX_VALUE);
    assertEquals(200, connection.getResponseCode());
    assertEquals("", connection.getResponseMessage()); // No response message for HTTP/2.0.
    assertEquals("HTTP-draft-04/2.0", connection.getHeaderField("OkHttp-Selected-Transport"));

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertContains(request.getHeaders(), ":scheme: https");
    assertContains(request.getHeaders(), ":host: " + hostName + ":" + server.getPort());
  }

  private <T> void assertContains(Collection<T> collection, T value) {
    assertTrue(collection.toString(), collection.contains(value));
  }

  private void assertContent(String expected, URLConnection connection, int limit)
      throws IOException {
    connection.connect();
    assertEquals(expected, readAscii(connection.getInputStream(), limit));
    ((HttpURLConnection) connection).disconnect();
  }

  private String readAscii(InputStream in, int count) throws IOException {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      int value = in.read();
      if (value == -1) {
        in.close();
        break;
      }
      result.append((char) value);
    }
    return result.toString();
  }
}
