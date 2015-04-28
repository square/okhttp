package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExpectedExchangesRuleTest {

  @ClassRule
  public static MockWebServerRule rule = new MockWebServerRule();
  @Rule
  public ExpectedExchangesRule expectedExchanges = new ExpectedExchangesRule(rule);

  @Test
  public void getShouldUsePredicatesToDetermineCorrectResponse() throws Throwable {

    final String path = "/randomPathName";
    final String expected = "expectedText";

    expectedExchanges.get(path).willReturn(new MockResponse().setBody(expected));

    final String actual = get(path);

    assertEquals(expected, actual);
  }

  @Test
  public void shouldBePossibleToDelaySendingAnyData() throws Throwable {

    final String path = "/anotherPath";
    final String expected = "iExpectThis";

    expectedExchanges.get(path).willReturn(new Supplier<MockResponse>() {
      @Override
      public MockResponse get() {
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new MockResponse().setBody(expected);
      }
    });

    final long start = System.currentTimeMillis();
    final String actual = get(path);
    final long end = System.currentTimeMillis();
    assertTrue((end - start) > 750L);

    assertEquals(expected, actual);
  }

  @Test
  public void postShouldUsePredicatesToDetermineCorrectResponse() throws Throwable {

    final String path = "/thisPath";
    final String expectedBody = "the body that is expected";
    final String expected = "expected response";

    expectedExchanges.post(path).with(RequestMatchers.body(new EqualityPredicate<>(expectedBody))).willReturn(new MockResponse().setBody(expected));

    final String actual = post(path, expectedBody);

    assertEquals(expected, actual);
  }

  @Test
  public void putShouldUsePredicatesToDetermineCorrectResponse() throws Throwable {

    final String path = "/pathToFind";
    final String expectedBody = "expected request body";
    final String expected = "expected response body";

    expectedExchanges.put(path).with(RequestMatchers.body(new EqualityPredicate<>(expectedBody))).willReturn(new MockResponse().setBody(expected));

    final String actual = put(path, expectedBody);

    assertEquals(expected, actual);
  }

  private String get(final String path) throws Throwable {

    URL url = rule.get().getUrl(path);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept-Language", "en-US");
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));

    return reader.readLine();
  }

  private String post(final String path, final String body) throws Throwable {

    URL url = rule.get().getUrl(path);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Accept-Charset", StandardCharsets.UTF_8.displayName());
    connection.setRequestProperty("Accept-Language", "en-US");
    try (final OutputStream output = connection.getOutputStream()) {
      output.write(body.getBytes(StandardCharsets.UTF_8));
    }
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));

    return reader.readLine();
  }

  private String put(final String path, final String body) throws Throwable {

    URL url = rule.get().getUrl(path);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("PUT");
    connection.setDoOutput(true);
    connection.setRequestProperty("Accept-Charset", StandardCharsets.UTF_8.displayName());
    connection.setRequestProperty("Accept-Language", "en-US");
    try (final OutputStream output = connection.getOutputStream()) {
      output.write(body.getBytes(StandardCharsets.UTF_8));
    }
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));

    return reader.readLine();
  }

}