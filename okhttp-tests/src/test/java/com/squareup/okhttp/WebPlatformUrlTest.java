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

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okio.BufferedSource;
import okio.Okio;
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
      List<Object[]> result = new ArrayList<>();
      for (WebPlatformUrlTestData urlTestData : loadTests()) {
        result.add(new Object[] { urlTestData });
      }
      return result;
    } catch (IOException e) {
      throw new AssertionError();
    }
  }

  @Parameter(0)
  public WebPlatformUrlTestData testData;

  private static final List<String> HTTP_URL_SCHEMES
      = Util.immutableList("http", "https");
  private static final List<String> KNOWN_FAILURES = Util.immutableList(
      "Parsing: <http://example\t.\norg> against <http://example.org/foo/bar>",
      "Parsing: <http://f:0/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:00000000000000/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:\n/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:999999/c> against <http://example.org/foo/bar>",
      "Parsing: <#β> against <http://example.org/foo/bar>",
      "Parsing: <http://www.google.com/foo?bar=baz# »> against <about:blank>",
      "Parsing: <http://192.0x00A80001> against <about:blank>",
      // This test fails on Java 7 but passes on Java 8. See HttpUrlTest.hostWithTrailingDot().
      "Parsing: <http://%30%78%63%30%2e%30%32%35%30.01%2e> against <http://other.com/>",
      "Parsing: <http://%30%78%63%30%2e%30%32%35%30.01> against <http://other.com/>",
      "Parsing: <http://192.168.0.257> against <http://other.com/>",
      "Parsing: <http://０Ｘｃ０．０２５０．０１> against <http://other.com/>",
      "Parsing: <http://[2001::1]> against <http://example.org/foo/bar>",
      "Parsing: <http://[2001::1]:80> against <http://example.org/foo/bar>",
      // TODO(jwilson): derive the exact rules on when ' ' maps to '+' vs. '%20'.
      "Parsing: <http://f:21/ b ? d # e > against <http://example.org/foo/bar>"
  );

  /** Test how {@link HttpUrl} does against the web platform test suite. */
  @Test public void httpUrl() throws Exception {
    if (!testData.scheme.isEmpty() && !HTTP_URL_SCHEMES.contains(testData.scheme)) {
      System.err.println("Ignoring unsupported scheme " + testData.scheme);
      return;
    }
    if (!testData.base.startsWith("https:")
        && !testData.base.startsWith("http:")
        && !testData.base.equals("about:blank")) {
      System.err.println("Ignoring unsupported base " + testData.base);
      return;
    }

    try {
      testHttpUrl();
      if (KNOWN_FAILURES.contains(testData.toString())) {
        System.err.println("Expected failure but was success: " + testData);
      }
    } catch (Throwable e) {
      if (KNOWN_FAILURES.contains(testData.toString())) {
        System.err.println("Ignoring known failure: " + testData);
        e.printStackTrace();
      } else {
        throw e;
      }
    }
  }

  private void testHttpUrl() {
    HttpUrl url;
    if (testData.base.equals("about:blank")) {
      url = HttpUrl.parse(testData.input);
    } else {
      HttpUrl baseUrl = HttpUrl.parse(testData.base);
      url = baseUrl.resolve(testData.input);
    }

    if (testData.expectParseFailure()) {
      assertNull("Expected URL to fail parsing", url);
    } else {
      assertNotNull("Expected URL to parse successfully, but was null", url);
      String effectivePort = url.port() != HttpUrl.defaultPort(url.scheme())
          ? Integer.toString(url.port())
          : "";
      String effectiveQuery = url.query() != null ? "?" + url.query() : "";
      String effectiveFragment = url.fragment() != null ? "#" + url.fragment() : "";
      String effectiveHost = url.host().contains(":")
          ? ("[" + url.host() + "]")
          : url.host();
      assertEquals("scheme", testData.scheme, url.scheme());
      assertEquals("host", testData.host, effectiveHost);
      assertEquals("port", testData.port, effectivePort);
      assertEquals("path", testData.path, url.path());
      assertEquals("query", testData.query, effectiveQuery);
      assertEquals("fragment", testData.fragment, effectiveFragment);
    }
  }

  private static List<WebPlatformUrlTestData> loadTests() throws IOException {
    BufferedSource source = Okio.buffer(Okio.source(
        WebPlatformUrlTest.class.getResourceAsStream("/web-platform-test-urltestdata.txt")));
    return WebPlatformUrlTestData.load(source);
  }
}
