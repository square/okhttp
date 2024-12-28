/*
 * Copyright (C) 2023 Square, Inc.
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

import assertk.fail
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.UrlComponentEncodingTester.Component

fun urlComponentEncodingTesterJvmPlatform(component: Component): UrlComponentEncodingTester.Platform {
  return when (component) {
    Component.USER ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri('%'.code)

    Component.PASSWORD ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri('%'.code)

    Component.HOST ->
      UrlComponentEncodingTesterJvmPlatform()
        .stripForUri(
          '\"'.code,
          '<'.code,
          '>'.code,
          '^'.code,
          '`'.code,
          '{'.code,
          '|'.code,
          '}'.code,
        )

    Component.PATH ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri(
          '%'.code,
          '['.code,
          ']'.code,
        )

    Component.QUERY ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri(
          '%'.code,
          '\\'.code,
          '^'.code,
          '`'.code,
          '{'.code,
          '|'.code,
          '}'.code,
        )

    Component.QUERY_VALUE ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri(
          '%'.code,
          '\\'.code,
          '^'.code,
          '`'.code,
          '{'.code,
          '|'.code,
          '}'.code,
        )

    Component.FRAGMENT ->
      UrlComponentEncodingTesterJvmPlatform()
        .escapeForUri(
          '%'.code,
          ' '.code,
          '"'.code,
          '#'.code,
          '<'.code,
          '>'.code,
          '\\'.code,
          '^'.code,
          '`'.code,
          '{'.code,
          '|'.code,
          '}'.code,
        )
  }
}

private class UrlComponentEncodingTesterJvmPlatform : UrlComponentEncodingTester.Platform() {
  private val uriEscapedCodePoints = StringBuilder()
  private val uriStrippedCodePoints = StringBuilder()

  /**
   * Configure code points to be escaped for conversion to `java.net.URI`. That class is more
   * strict than the others.
   */
  fun escapeForUri(vararg codePoints: Int) =
    apply {
      uriEscapedCodePoints.append(String(*codePoints))
    }

  /**
   * Configure code points to be stripped in conversion to `java.net.URI`. That class is more
   * strict than the others.
   */
  fun stripForUri(vararg codePoints: Int) =
    apply {
      uriStrippedCodePoints.append(String(*codePoints))
    }

  override fun test(
    codePoint: Int,
    codePointString: String,
    encoding: UrlComponentEncodingTester.Encoding,
    component: Component,
  ) {
    testToUrl(codePoint, encoding, component)
    testFromUrl(codePoint, encoding, component)
    testUri(codePoint, codePointString, encoding, component)
  }

  private fun testToUrl(
    codePoint: Int,
    encoding: UrlComponentEncodingTester.Encoding,
    component: Component,
  ) {
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val javaNetUrl = httpUrl.toUrl()
    if (javaNetUrl.toString() != javaNetUrl.toString()) {
      fail("Encoding $component $codePoint using $encoding")
    }
  }

  private fun testFromUrl(
    codePoint: Int,
    encoding: UrlComponentEncodingTester.Encoding,
    component: Component,
  ) {
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val toAndFromJavaNetUrl = httpUrl.toUrl().toHttpUrlOrNull()
    if (toAndFromJavaNetUrl != httpUrl) {
      fail("Encoding $component $codePoint using $encoding")
    }
  }

  private fun testUri(
    codePoint: Int,
    codePointString: String,
    encoding: UrlComponentEncodingTester.Encoding,
    component: Component,
  ) {
    if (codePoint == '%'.code) return
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val uri = httpUrl.toUri()
    val toAndFromUri = uri.toHttpUrlOrNull()
    val uriStripped = uriStrippedCodePoints.indexOf(codePointString) != -1
    if (uriStripped) {
      if (uri.toString() != component.urlString("")) {
        fail("Encoding $component $codePoint using $encoding")
      }
      return
    }

    // If the URI has more escaping than the HttpURL, check that the decoded values still match.
    val uriEscaped = uriEscapedCodePoints.indexOf(codePointString) != -1
    if (uriEscaped) {
      if (uri.toString() == httpUrl.toString()) {
        fail("Encoding $component $codePoint using $encoding")
      }
      if (component[toAndFromUri!!] != codePointString) {
        fail("Encoding $component $codePoint using $encoding")
      }
      return
    }

    // Check that the URI and HttpURL have the exact same escaping.
    if (toAndFromUri != httpUrl) {
      fail("Encoding $component $codePoint using $encoding")
    }
    if (uri.toString() != httpUrl.toString()) {
      fail("Encoding $component $codePoint using $encoding")
    }
  }
}
