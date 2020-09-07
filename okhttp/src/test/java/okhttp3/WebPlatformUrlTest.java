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
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import static okhttp3.internal.Util.immutableListOf;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the web platform URL tests against Java URL models. */
@RunWith(Parameterized.class)
public final class WebPlatformUrlTest {
  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> parameters() {
    try {
      List<Object[]> result = new ArrayList<>();
      for (WebPlatformUrlTestData urlTestData : loadTests()) {
        result.add(new Object[] {urlTestData});
      }
      return result;
    } catch (IOException e) {
      throw new AssertionError();
    }
  }

  @Parameter
  public WebPlatformUrlTestData testData;

  private static final List<String> HTTP_URL_SCHEMES = immutableListOf("http", "https");
  private static final List<String> KNOWN_FAILURES = immutableListOf(
      "Parsing: <http://example\t.\norg> against <http://example.org/foo/bar>",
      "Parsing: <http://f:0/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:00000000000000/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:\n/c> against <http://example.org/foo/bar>",
      "Parsing: <http://f:999999/c> against <http://example.org/foo/bar>",
      "Parsing: <http://192.0x00A80001> against <about:blank>",
      "Parsing: <http://%30%78%63%30%2e%30%32%35%30.01> against <http://other.com/>",
      "Parsing: <http://192.168.0.257> against <http://other.com/>",
      "Parsing: <http://０Ｘｃ０．０２５０．０１> against <http://other.com/>"
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
      HttpUrl baseUrl = HttpUrl.get(testData.base);
      url = baseUrl.resolve(testData.input);
    }

    if (testData.expectParseFailure()) {
      assertThat(url).overridingErrorMessage("Expected URL to fail parsing").isNull();
    } else {
      assertThat(url).overridingErrorMessage(
          "Expected URL to parse successfully, but was null").isNotNull();
      String effectivePort = url.port() != HttpUrl.defaultPort(url.scheme())
          ? Integer.toString(url.port())
          : "";
      String effectiveQuery = url.encodedQuery() != null ? "?" + url.encodedQuery() : "";
      String effectiveFragment = url.encodedFragment() != null ? "#" + url.encodedFragment() : "";
      String effectiveHost = url.host().contains(":")
          ? ("[" + url.host() + "]")
          : url.host();
      assertThat(url.scheme()).overridingErrorMessage("scheme").isEqualTo(testData.scheme);
      assertThat(effectiveHost).overridingErrorMessage("host").isEqualTo(testData.host);
      assertThat(effectivePort).overridingErrorMessage("port").isEqualTo(testData.port);
      assertThat(url.encodedPath()).overridingErrorMessage("path").isEqualTo(testData.path);
      assertThat(effectiveQuery).overridingErrorMessage("query").isEqualTo(testData.query);
      assertThat(effectiveFragment).overridingErrorMessage("fragment").isEqualTo(
          testData.fragment);
    }
  }

  private static List<WebPlatformUrlTestData> loadTests() throws IOException {
    BufferedSource source = Okio.buffer(Okio.source(
        WebPlatformUrlTest.class.getResourceAsStream("/web-platform-test-urltestdata.txt")));
    return WebPlatformUrlTestData.load(source);
  }
}
