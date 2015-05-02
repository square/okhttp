/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.WebPlatformTestRun.SubtestResult;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Runs the web platform URL tests against Java URL models. */
@RunWith(Parameterized.class)
public final class WebPlatformUrlTest {
  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> parameters() {
    try {
      List<WebPlatformUrlTestData> tests = loadTests();

      // The web platform tests are run in both HTML and XHTML variants. Major browsers pass more
      // tests in HTML mode, so that's what we'll attempt to match.
      String testName = "/url/a-element.html";
      WebPlatformTestRun firefoxTestRun
          = loadTestRun("/web-platform-test-results-url-firefox-37.0.json");
      WebPlatformTestRun chromeTestRun
          = loadTestRun("/web-platform-test-results-url-chrome-42.0.json");
      WebPlatformTestRun safariTestRun
          = loadTestRun("/web-platform-test-results-url-safari-7.1.json");

      List<Object[]> result = new ArrayList<>();
      for (WebPlatformUrlTestData urlTestData : tests) {
        String subtestName = urlTestData.toString();
        SubtestResult firefoxResult = firefoxTestRun.get(testName, subtestName);
        SubtestResult chromeResult = chromeTestRun.get(testName, subtestName);
        SubtestResult safariResult = safariTestRun.get(testName, subtestName);
        result.add(new Object[] { urlTestData, firefoxResult, chromeResult, safariResult });
      }
      return result;
    } catch (IOException e) {
      throw new AssertionError();
    }
  }

  @Parameter(0)
  public WebPlatformUrlTestData testData;

  @Parameter(1)
  public SubtestResult firefoxResult;

  @Parameter(2)
  public SubtestResult chromeResultResult;

  @Parameter(3)
  public SubtestResult safariResult;

  private static final List<String> JAVA_NET_URL_SCHEMES
      = Util.immutableList("file", "ftp", "http", "https", "mailto");

  /** Test how java.net.URL does against the web platform test suite. */
  @Ignore // java.net.URL is broken. Not much we can do about that.
  @Test public void javaNetUrl() throws Exception {
    if (!testData.scheme.isEmpty() && !JAVA_NET_URL_SCHEMES.contains(testData.scheme)) {
      System.out.println("Ignoring unsupported scheme " + testData.scheme);
      return;
    }

    try {
      testJavaNetUrl();
    } catch (AssertionError e) {
      if (tolerateFailure()) {
        System.out.println("Tolerable failure: " + e.getMessage());
        return;
      }
      throw e;
    }
  }

  private void testJavaNetUrl() {
    URL url = null;
    String failureMessage = "";
    try {
      if (testData.base.equals("about:blank")) {
        url = new URL(testData.input);
      } else {
        URL baseUrl = new URL(testData.base);
        url = new URL(baseUrl, testData.input);
      }
    } catch (MalformedURLException e) {
      failureMessage = e.getMessage();
    }

    if (testData.expectParseFailure()) {
      assertNull("Expected URL to fail parsing", url);
    } else {
      assertNotNull("Expected URL to parse successfully, but was " + failureMessage, url);
      String effectivePort = url.getPort() != -1 ? Integer.toString(url.getPort()) : "";
      String effectiveQuery = url.getQuery() != null ? "?" + url.getQuery() : "";
      String effectiveFragment = url.getRef() != null ? "#" + url.getRef() : "";
      assertEquals("scheme", testData.scheme, url.getProtocol());
      assertEquals("host", testData.host, url.getHost());
      assertEquals("port", testData.port, effectivePort);
      assertEquals("path", testData.path, url.getPath());
      assertEquals("query", testData.query, effectiveQuery);
      assertEquals("fragment", testData.fragment, effectiveFragment);
    }
  }

  /**
   * Returns true if several major browsers also fail this test, in which case the test itself is
   * questionable.
   */
  private boolean tolerateFailure() {
    return !firefoxResult.isPass()
        && !chromeResultResult.isPass()
        && !safariResult.isPass();
  }

  private static List<WebPlatformUrlTestData> loadTests() throws IOException {
    BufferedSource source = Okio.buffer(Okio.source(
        WebPlatformUrlTest.class.getResourceAsStream("/web-platform-test-urltestdata.txt")));
    return WebPlatformUrlTestData.load(source);
  }

  private static WebPlatformTestRun loadTestRun(String name) throws IOException {
    return WebPlatformTestRun.load(WebPlatformUrlTest.class.getResourceAsStream(name));
  }
}
