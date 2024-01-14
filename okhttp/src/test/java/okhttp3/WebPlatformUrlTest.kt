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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.buffer
import okio.source
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

/** Runs the web platform URL tests against Java URL models.  */
class WebPlatformUrlTest {
  class TestDataParamProvider : SimpleProvider() {
    override fun arguments() = ArrayList<Any>(loadTests())
  }

  /** Test how [HttpUrl] does against the web platform test suite.  */
  @ArgumentsSource(TestDataParamProvider::class)
  @ParameterizedTest
  fun httpUrl(testData: WebPlatformUrlTestData) {
    if (!testData.scheme.isEmpty() && !HTTP_URL_SCHEMES.contains(testData.scheme)) {
      System.err.println("Ignoring unsupported scheme ${testData.scheme}")
      return
    }

    if (!testData.base!!.startsWith("https:") &&
      !testData.base!!.startsWith("http:") &&
      testData.base != "about:blank"
    ) {
      System.err.println("Ignoring unsupported base ${testData.base}")
      return
    }

    try {
      testHttpUrl(testData)
      if (KNOWN_FAILURES.contains(testData.toString())) {
        System.err.println("Expected failure but was success: $testData")
      }
    } catch (e: Throwable) {
      if (KNOWN_FAILURES.contains(testData.toString())) {
        System.err.println("Ignoring known failure: $testData")
        e.printStackTrace()
      } else {
        throw e
      }
    }
  }

  private fun testHttpUrl(testData: WebPlatformUrlTestData) {
    val url =
      when (testData.base) {
        "about:blank" -> testData.input!!.toHttpUrlOrNull()
        else -> testData.base!!.toHttpUrl().resolve(testData.input!!)
      }

    if (testData.expectParseFailure()) {
      assertThat(url, "Expected URL to fail parsing").isNull()
      return
    }

    assertThat(url, "Expected URL to parse successfully, but was null")
      .isNotNull()
    val effectivePort =
      when {
        url!!.port != defaultPort(url.scheme) -> Integer.toString(url.port)
        else -> ""
      }
    val effectiveQuery =
      when {
        url.encodedQuery != null -> "?" + url.encodedQuery
        else -> ""
      }
    val effectiveFragment =
      when {
        url.encodedFragment != null -> "#" + url.encodedFragment
        else -> ""
      }
    val effectiveHost =
      when {
        url.host.contains(":") -> "[" + url.host + "]"
        else -> url.host
      }
    assertThat(url.scheme, "scheme").isEqualTo(testData.scheme)
    assertThat(effectiveHost, "host").isEqualTo(testData.host)
    assertThat(effectivePort, "port").isEqualTo(testData.port)
    assertThat(url.encodedPath, "path").isEqualTo(testData.path)
    assertThat(effectiveQuery, "query").isEqualTo(testData.query)
    assertThat(effectiveFragment, "fragment").isEqualTo(testData.fragment)
  }

  companion object {
    private val HTTP_URL_SCHEMES = listOf("http", "https")
    private val KNOWN_FAILURES =
      listOf(
        "Parsing: <http://example\t.\norg> against <http://example.org/foo/bar>",
        "Parsing: <http://f:0/c> against <http://example.org/foo/bar>",
        "Parsing: <http://f:00000000000000/c> against <http://example.org/foo/bar>",
        "Parsing: <http://f:\n/c> against <http://example.org/foo/bar>",
        "Parsing: <http://f:999999/c> against <http://example.org/foo/bar>",
        "Parsing: <http://192.0x00A80001> against <about:blank>",
        "Parsing: <http://%30%78%63%30%2e%30%32%35%30.01> against <http://other.com/>",
        "Parsing: <http://192.168.0.257> against <http://other.com/>",
        "Parsing: <http://０Ｘｃ０．０２５０．０１> against <http://other.com/>",
      )

    private fun loadTests(): List<WebPlatformUrlTestData> {
      val resourceAsStream =
        WebPlatformUrlTest::class.java.getResourceAsStream(
          "/web-platform-test-urltestdata.txt",
        )
      val source = resourceAsStream.source().buffer()
      return WebPlatformUrlTestData.load(source)
    }
  }
}
