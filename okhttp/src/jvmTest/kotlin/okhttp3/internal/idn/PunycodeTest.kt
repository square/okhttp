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
package okhttp3.internal.idn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunycodeTest {
  /** https://datatracker.ietf.org/doc/html/rfc3492#section-7.1 */
  @Test fun rfc3492Samples() {
    // (A) Arabic (Egyptian)
    testEncodeDecode(
      unicode = "ليهمابتكلموشعربي؟",
      punycode = "xn--egbpdaj6bu4bxfgehfvwxn",
    )

    // (B) Chinese (simplified)
    testEncodeDecode(
      unicode = "他们为什么不说中文",
      punycode = "xn--ihqwcrb4cv8a8dqg056pqjye",
    )

    // (C) Chinese (traditional)
    testEncodeDecode(
      unicode = "他們爲什麽不說中文",
      punycode = "xn--ihqwctvzc91f659drss3x8bo0yb",
    )

    // (D) Czech
    testEncodeDecode(
      unicode = "Pročprostěnemluvíčesky",
      punycode = "xn--Proprostnemluvesky-uyb24dma41a",
    )

    // (E) Hebrew:
    testEncodeDecode(
      unicode = "למההםפשוטלאמדבריםעברית",
      punycode = "xn--4dbcagdahymbxekheh6e0a7fei0b",
    )

    // (F) Hindi (Devanagari)
    testEncodeDecode(
      unicode = "यहलोगहिन्दीक्योंनहींबोलसकतेहैं",
      punycode = "xn--i1baa7eci9glrd9b2ae1bj0hfcgg6iyaf8o0a1dig0cd",
    )

    // (G) Japanese (kanji and hiragana)
    testEncodeDecode(
      unicode = "なぜみんな日本語を話してくれないのか",
      punycode = "xn--n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa",
    )

    // (H) Korean (Hangul syllables)
    testEncodeDecode(
      unicode = "세계의모든사람들이한국어를이해한다면얼마나좋을까",
      punycode = "xn--989aomsvi5e83db1d2a355cv1e0vak1dwrv93d5xbh15a0dt30a5jpsd879ccm6fea98c",
    )

    // (I) Russian (Cyrillic)
    testEncodeDecode(
      unicode = "почемужеонинеговорятпорусски",
      punycode = "xn--b1abfaaepdrnnbgefbadotcwatmq2g4l",
    )

    // (J) Spanish
    testEncodeDecode(
      unicode = "PorquénopuedensimplementehablarenEspañol",
      punycode = "xn--PorqunopuedensimplementehablarenEspaol-fmd56a",
    )

    // (K) Vietnamese
    testEncodeDecode(
      unicode = "TạisaohọkhôngthểchỉnóitiếngViệt",
      punycode = "xn--TisaohkhngthchnitingVit-kjcr8268qyxafd2f1b9g",
    )
  }

  @Test fun multipleLabels() {
    testEncodeDecode(
      unicode = "☃.net",
      punycode = "xn--n3h.net",
    )
    testEncodeDecode(
      unicode = "ålgård.no",
      punycode = "xn--lgrd-poac.no",
    )
    testEncodeDecode(
      unicode = "個人.香港",
      punycode = "xn--gmqw5a.xn--j6w193g",
    )
    testEncodeDecode(
      unicode = "упр.срб",
      punycode = "xn--o1ach.xn--90a3ac",
    )
  }

  @Test fun nonBasicCodePointInPrefix() {
    assertNull(Punycode.decode("xn--cåt-n3h"))
  }

  @Test fun nonBasicCodePointInInsertionCoding() {
    assertNull(Punycode.decode("xn--cat-ñ3h"))
  }

  @Test fun unterminatedCodePoint() {
    assertNull(Punycode.decode("xn--cat-n"))
  }

  @Test fun overflowI() {
    assertNull(Punycode.decode("xn--99999999"))
  }

  @Test fun overflowMaxCodePoint() {
    assertNull(Punycode.decode("xn--a-b.net"))
    assertNull(Punycode.decode("xn--a-9b.net"))
    assertEquals("a՚.net", Punycode.decode("xn--a-99b.net"))
    assertEquals("a溠.net", Punycode.decode("xn--a-999b.net"))
    assertEquals("a\uD8E2\uDF5C.net", Punycode.decode("xn--a-9999b.net"))
    assertNull(Punycode.decode("xn--a-99999b.net"))
  }

  @Test fun dashInPrefix() {
    testEncodeDecode(
      unicode = "klmnöpqrst-uvwxy",
      punycode = "xn--klmnpqrst-uvwxy-ctb",
    )
  }

  @Test fun uppercasePunycode() {
    testDecodeOnly(
      unicode = "ليهمابتكلموشعربي؟",
      punycode = "XN--EGBPDAJ6BU4BXFGEHFVWXN",
    )
  }

  @Test fun mixedCasePunycode() {
    testDecodeOnly(
      unicode = "ليهمابتكلموشعربي؟",
      punycode = "Xn--EgBpDaJ6Bu4bXfGeHfVwXn",
    )
  }

  /**
   * It's invalid to have a label longer than 63 characters. If that's requested, the encoder may
   * overflow and return null.
   */
  @Test fun overflowEncodingOversizedLabel() {
    val a1000 = "a".repeat(1000)
    val a1000MaxCodePoint = a1000 + "\udbff\udfff"
    testEncodeDecode(
      a1000MaxCodePoint,
      "xn--$a1000-nc89312g",
    )
    assertNull(
      Punycode.encode(a1000MaxCodePoint.repeat(2)),
    )
  }

  @Test fun invalidPunycode() {
    assertNull(Punycode.decode("xn--ls8h="))
  }

  private fun testEncodeDecode(
    unicode: String,
    punycode: String,
  ) {
    assertEquals(unicode, Punycode.decode(punycode))
    assertEquals(punycode, Punycode.encode(unicode))
  }

  private fun testDecodeOnly(
    unicode: String,
    punycode: String,
  ) {
    assertEquals(unicode, Punycode.decode(punycode))
  }
}
