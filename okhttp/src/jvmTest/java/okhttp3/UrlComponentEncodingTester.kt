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

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.format
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.fail

/** Tests how each code point is encoded and decoded in the context of each URL component.  */
internal class UrlComponentEncodingTester private constructor() {
  private val encodings: MutableMap<Int, Encoding> = LinkedHashMap()
  private val uriEscapedCodePoints = StringBuilder()
  private val uriStrippedCodePoints = StringBuilder()

  private fun allAscii(encoding: Encoding) = apply {
    for (i in 0..127) {
      encodings[i] = encoding
    }
  }

  fun override(encoding: Encoding, vararg codePoints: Int) = apply {
    for (codePoint in codePoints) {
      encodings[codePoint] = encoding
    }
  }

  fun nonPrintableAscii(encoding: Encoding) = apply {
    encodings[      0x0] = encoding // Null character
    encodings[      0x1] = encoding // Start of Header
    encodings[      0x2] = encoding // Start of Text
    encodings[      0x3] = encoding // End of Text
    encodings[      0x4] = encoding // End of Transmission
    encodings[      0x5] = encoding // Enquiry
    encodings[      0x6] = encoding // Acknowledgment
    encodings[      0x7] = encoding // Bell
    encodings['\b'.code] = encoding // Backspace
    encodings[      0xb] = encoding // Vertical Tab
    encodings[      0xe] = encoding // Shift Out
    encodings[      0xf] = encoding // Shift In
    encodings[     0x10] = encoding // Data Link Escape
    encodings[     0x11] = encoding // Device Control 1 (oft. XON)
    encodings[     0x12] = encoding // Device Control 2
    encodings[     0x13] = encoding // Device Control 3 (oft. XOFF)
    encodings[     0x14] = encoding // Device Control 4
    encodings[     0x15] = encoding // Negative Acknowledgment
    encodings[     0x16] = encoding // Synchronous idle
    encodings[     0x17] = encoding // End of Transmission Block
    encodings[     0x18] = encoding // Cancel
    encodings[     0x19] = encoding // End of Medium
    encodings[     0x1a] = encoding // Substitute
    encodings[     0x1b] = encoding // Escape
    encodings[     0x1c] = encoding // File Separator
    encodings[     0x1d] = encoding // Group Separator
    encodings[     0x1e] = encoding // Record Separator
    encodings[     0x1f] = encoding // Unit Separator
    encodings[     0x7f] = encoding // Delete
  }

  fun nonAscii(encoding: Encoding) = apply {
    encodings[UNICODE_2] = encoding
    encodings[UNICODE_3] = encoding
    encodings[UNICODE_4] = encoding
  }

  /**
   * Configure code points to be escaped for conversion to `java.net.URI`. That class is more
   * strict than the others.
   */
  fun escapeForUri(vararg codePoints: Int) = apply {
    uriEscapedCodePoints.append(String(codePoints, 0, codePoints.size))
  }

  /**
   * Configure code points to be stripped in conversion to `java.net.URI`. That class is more
   * strict than the others.
   */
  fun stripForUri(vararg codePoints: Int) = apply {
    uriStrippedCodePoints.append(String(codePoints, 0, codePoints.size))
  }

  fun test(component: Component) = apply {
    for ((codePoint, encoding) in encodings) {
      val codePointString = Encoding.IDENTITY.encode(codePoint)
      if (encoding == Encoding.FORBIDDEN) {
        testForbidden(codePoint, codePointString, component)
        continue
      }
      testEncodeAndDecode(codePoint, codePointString, component)
      if (encoding == Encoding.SKIP) continue
      testParseOriginal(codePoint, codePointString, encoding, component)
      testParseAlreadyEncoded(codePoint, encoding, component)
      testToUrl(codePoint, encoding, component)
      testFromUrl(codePoint, encoding, component)
      testUri(codePoint, codePointString, encoding, component)
    }
  }

  private fun testParseAlreadyEncoded(codePoint: Int, encoding: Encoding, component: Component) {
    val expected = component.canonicalize(encoding.encode(codePoint))
    val urlString = component.urlString(expected)
    val url = urlString.toHttpUrl()
    val actual = component.encodedValue(url)
    if (actual != expected) {
      fail<Any>(
        format(
          "Encoding %s %#x using %s: '%s' != '%s'",
          component, codePoint, encoding, actual, expected
        )
      )
    }
  }

  private fun testEncodeAndDecode(codePoint: Int, codePointString: String, component: Component) {
    val builder = "http://host/".toHttpUrl().newBuilder()
    component[builder] = codePointString
    val url = builder.build()
    val expected = component.canonicalize(codePointString)
    val actual = component[url]
    if (expected != actual) {
      fail<Any>(format("Roundtrip %s %#x %s", component, codePoint, url))
    }
  }

  private fun testParseOriginal(
    codePoint: Int, codePointString: String, encoding: Encoding, component: Component
  ) {
    val expected = encoding.encode(codePoint)
    if (encoding !== Encoding.PERCENT) return
    val urlString = component.urlString(codePointString)
    val url = urlString.toHttpUrl()
    val actual = component.encodedValue(url)
    if (actual != expected) {
      fail<Any>(
        format(
          "Encoding %s %#02x using %s: '%s' != '%s'",
          component, codePoint, encoding, actual, expected
        )
      )
    }
  }

  private fun testToUrl(codePoint: Int, encoding: Encoding, component: Component) {
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val javaNetUrl = httpUrl.toUrl()
    if (javaNetUrl.toString() != javaNetUrl.toString()) {
      fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
    }
  }

  private fun testFromUrl(codePoint: Int, encoding: Encoding, component: Component) {
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val toAndFromJavaNetUrl = httpUrl.toUrl().toHttpUrlOrNull()
    if (toAndFromJavaNetUrl != httpUrl) {
      fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
    }
  }

  private fun testUri(
    codePoint: Int, codePointString: String, encoding: Encoding, component: Component
  ) {
    if (codePoint == '%'.code) return
    val encoded = encoding.encode(codePoint)
    val httpUrl = component.urlString(encoded).toHttpUrl()
    val uri = httpUrl.toUri()
    val toAndFromUri = uri.toHttpUrlOrNull()
    val uriStripped = uriStrippedCodePoints.indexOf(codePointString) != -1
    if (uriStripped) {
      if (uri.toString() != component.urlString("")) {
        fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
      }
      return
    }

    // If the URI has more escaping than the HttpURL, check that the decoded values still match.
    val uriEscaped = uriEscapedCodePoints.indexOf(codePointString) != -1
    if (uriEscaped) {
      if (uri.toString() == httpUrl.toString()) {
        fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
      }
      if (component[toAndFromUri!!] != codePointString) {
        fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
      }
      return
    }

    // Check that the URI and HttpURL have the exact same escaping.
    if (toAndFromUri != httpUrl) {
      fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
    }
    if (uri.toString() != httpUrl.toString()) {
      fail<Any>(format("Encoding %s %#x using %s", component, codePoint, encoding))
    }
  }

  private fun testForbidden(codePoint: Int, codePointString: String, component: Component) {
    val builder = "http://host/".toHttpUrl().newBuilder()
    try {
      component[builder] = codePointString
      fail<Any>(format("Accepted forbidden code point %s %#x", component, codePoint))
    } catch (expected: IllegalArgumentException) {
    }
  }

  enum class Encoding {
    IDENTITY {
      override fun encode(codePoint: Int): String {
        return String(intArrayOf(codePoint), 0, 1)
      }
    },
    PERCENT {
      override fun encode(codePoint: Int): String {
        val utf8 = IDENTITY.encode(codePoint).encodeUtf8()
        val percentEncoded = Buffer()
        for (i in 0 until utf8.size) {
          percentEncoded.writeUtf8(format("%%%02X", utf8[i].toInt() and 0xff))
        }
        return percentEncoded.readUtf8()
      }
    },

    /** URLs that contain this character in this component are invalid.  */
    FORBIDDEN,

    /** This code point is special and should not be tested.  */
    SKIP;

    open fun encode(codePoint: Int): String {
      throw UnsupportedOperationException()
    }
  }

  enum class Component {
    USER {
      override fun urlString(value: String): String = "http://$value@example.com/"
      override fun encodedValue(url: HttpUrl): String = url.encodedUsername
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.username(value)
      }
      override operator fun get(url: HttpUrl): String = url.username
    },

    PASSWORD {
      override fun urlString(value: String): String = "http://:$value@example.com/"
      override fun encodedValue(url: HttpUrl): String = url.encodedPassword
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.password(value)
      }
      override operator fun get(url: HttpUrl): String = url.password
    },

    HOST {
      override fun urlString(value: String): String = "http://a${value}z.com/"
      override fun encodedValue(url: HttpUrl): String = get(url)
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.host("a${value}z.com")
      }
      override operator fun get(url: HttpUrl): String {
        val host = url.host
        return host.substring(1, host.length - 5).lowercase()
      }
      override fun canonicalize(s: String): String = s.lowercase()
    },

    PATH {
      override fun urlString(value: String): String = "http://example.com/a${value}z/"
      override fun encodedValue(url: HttpUrl): String {
        val path = url.encodedPath
        return path.substring(2, path.length - 2)
      }
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.addPathSegment("a${value}z")
      }
      override operator fun get(url: HttpUrl): String {
        val pathSegment = url.pathSegments[0]
        return pathSegment.substring(1, pathSegment.length - 1)
      }
    },

    QUERY {
      override fun urlString(value: String): String = "http://example.com/?a${value}z"
      override fun encodedValue(url: HttpUrl): String {
        val query = url.encodedQuery
        return query!!.substring(1, query.length - 1)
      }
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.query("a${value}z")
      }
      override operator fun get(url: HttpUrl): String {
        val query = url.query
        return query!!.substring(1, query.length - 1)
      }
    },

    QUERY_VALUE {
      override fun urlString(value: String): String = "http://example.com/?q=a${value}z"
      override fun encodedValue(url: HttpUrl): String {
        val query = url.encodedQuery
        return query!!.substring(3, query.length - 1)
      }
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.addQueryParameter("q", "a${value}z")
      }
      override operator fun get(url: HttpUrl): String {
        val value = url.queryParameter("q")
        return value!!.substring(1, value.length - 1)
      }
    },

    FRAGMENT {
      override fun urlString(value: String): String = "http://example.com/#a${value}z"
      override fun encodedValue(url: HttpUrl): String {
        val fragment = url.encodedFragment
        return fragment!!.substring(1, fragment.length - 1)
      }
      override operator fun set(builder: HttpUrl.Builder, value: String) {
        builder.fragment("a${value}z")
      }
      override operator fun get(url: HttpUrl): String {
        val fragment = url.fragment
        return fragment!!.substring(1, fragment.length - 1)
      }
    };

    abstract fun urlString(value: String): String
    abstract fun encodedValue(url: HttpUrl): String
    abstract operator fun set(builder: HttpUrl.Builder, value: String)
    abstract operator fun get(url: HttpUrl): String

    /**
     * Returns a character equivalent to 's' in this component. This is used to convert hostname
     * characters to lowercase.
     */
    open fun canonicalize(s: String): String = s
  }

  companion object {
    /** Arbitrary code point that's 2 bytes in UTF-8. */
    private const val UNICODE_2 = 0x07ff
    /** Arbitrary code point that's 3 bytes in UTF-8. */
    private const val UNICODE_3 = 0xffff
    /** Arbitrary code point that's 4 bytes in UTF-8. */
    private const val UNICODE_4 = 0x10ffff

    /**
     * Returns a new instance configured with a default encode set for the ASCII range. The specific
     * rules vary per-component: for example, '?' may be identity-encoded in a fragment, but must be
     * percent-encoded in a path.
     *
     * See https://url.spec.whatwg.org/#percent-encoded-bytes
     */
    @JvmStatic fun newInstance(): UrlComponentEncodingTester {
      return UrlComponentEncodingTester()
        .allAscii(Encoding.IDENTITY)
        .nonPrintableAscii(Encoding.PERCENT)
        .override(
          Encoding.SKIP,
          '\t'.code,
          '\n'.code,
          '\u000c'.code,
          '\r'.code
        )
        .override(
          Encoding.PERCENT,
          ' '.code,
          '"'.code,
          '#'.code,
          '<'.code,
          '>'.code,
          '?'.code,
          '`'.code
        )
        .override(
          Encoding.PERCENT,
          UNICODE_2,
          UNICODE_3,
          UNICODE_4
        )
    }
  }
}
