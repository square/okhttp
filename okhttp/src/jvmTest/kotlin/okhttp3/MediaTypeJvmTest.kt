/*
 * Copyright (C) 2022 Square, Inc.
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

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.internal.platform.Platform.Companion.isAndroid
import org.junit.jupiter.api.Test

class MediaTypeJvmTest : MediaTypeGetTest() {
  override fun MediaType.charsetName(): String? = this.charset()?.name()

  @Test fun testCharsetNameIsDoubleQuotedAndSingleQuotedAndroid() {
    val mediaType = "text/plain;charset=\"'utf-8'\"".toMediaType()
    if (isAndroid) {
      // Charset.forName("'utf-8'") == UTF-8
      assertEquals("UTF-8", mediaType.charsetName())
    } else {
      assertNull(mediaType.charset())
    }
  }

  @Test fun testDefaultCharset() {
    val noCharset = parse("text/plain")
    assertEquals(
      "UTF-8",
      noCharset.charset(Charsets.UTF_8)!!.name(),
    )
    assertEquals(
      "US-ASCII",
      noCharset.charset(Charsets.US_ASCII)!!.name(),
    )
    val charset = parse("text/plain; charset=iso-8859-1")
    assertEquals(
      "ISO-8859-1",
      charset.charset(Charsets.UTF_8)!!.name(),
    )
    assertEquals(
      "ISO-8859-1",
      charset.charset(Charsets.US_ASCII)!!.name(),
    )
  }

  @Test fun testTurkishDotlessIWithEnUs() {
    withLocale(Locale("en", "US")) {
      val mediaType = parse("IMAGE/JPEG")
      assertEquals("image", mediaType.type)
      assertEquals("jpeg", mediaType.subtype)
    }
  }

  @Test fun testTurkishDotlessIWithTrTr() {
    withLocale(Locale("tr", "TR")) {
      val mediaType = parse("IMAGE/JPEG")
      assertEquals("image", mediaType.type)
      assertEquals("jpeg", mediaType.subtype)
    }
  }

  private fun <T> withLocale(
    locale: Locale,
    block: () -> T,
  ): T {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(locale)
      return block()
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test fun testIllegalCharsetName() {
    val mediaType = parse("text/plain; charset=\"!@#$%^&*()\"")
    assertNull(mediaType.charsetName())
  }

  @Test fun testUnsupportedCharset() {
    val mediaType = parse("text/plain; charset=utf-wtf")
    assertNull(mediaType.charsetName())
  }

  @Test fun testCharsetNameIsDoubleQuotedAndSingleQuoted() {
    val mediaType = parse("text/plain;charset=\"'utf-8'\"")
    assertNull(mediaType.charsetName())
  }

  @Test fun testCharsetNameIsDoubleQuotedSingleQuote() {
    val mediaType = parse("text/plain;charset=\"'\"")
    assertNull(mediaType.charsetName())
  }
}
