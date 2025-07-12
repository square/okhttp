package com.squareup.okhttp3.maventest;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Headers;
import org.junit.Test;

import java.io.IOException;

/**
 * Unit test for simple App.
 */
public class AppTest {
  private final MockWebServer mockWebServer = new MockWebServer();

  @Test
  public void testApp() throws IOException {
    mockWebServer.enqueue(new MockResponse(200, Headers.of(), "Hello, Maven!"));
    mockWebServer.start();

    new SampleHttpClient().makeCall(mockWebServer.url("/"));
  }
}
