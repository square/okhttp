package com.squareup.okhttp.internal.huc;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

import static org.junit.Assert.fail;

public class HttpUrlConnectionImplTest {
  @Rule public MockWebServerRule serverRule = new MockWebServerRule();

  private OkHttpClient client = new OkHttpClient();
  private MockWebServer server;

  @Before public void setUp() {
    server = serverRule.get();
  }

  /**
   * Response code 407 should only come from proxy servers. Android's client
   * throws if it is sent by an origin server.
   */
  @Test public void originServerSends407() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(407));

    URL url = server.getUrl("/");
    HttpURLConnection conn = client.open(url);
    try {
      conn.getResponseCode();
      fail();
    } catch (IOException ignored) {
    }
  }

  /**
   * Follow Up URL with illegal characters should not throw IOException.
   */
  @Test public void followUpURLHasIllegalCharacter() {
    MockResponse mockResponse = new MockResponse();
    mockResponse.setResponseCode(302);
    mockResponse.addHeader("Location", "http://example.com/index.php?t=A|B|C");

    server.enqueue(mockResponse);
    server.enqueue(new MockResponse());
    URL url = server.getUrl("/");
    HttpURLConnection conn = client.open(url);

    try {
      conn.getResponseCode();
    } catch (IOException e) {
      fail();
    }
  }
}
