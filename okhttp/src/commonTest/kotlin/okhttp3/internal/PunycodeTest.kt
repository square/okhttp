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
package okhttp3.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class PunycodeTest {
  /** https://datatracker.ietf.org/doc/html/rfc3492#section-7.1 */
  @Test
  fun multipleLabels() {
    testDecode(
      unicode = "☃.net",
      punycode = "xn--n3h.net"
    )
    testDecode(
      unicode = "ålgård.no",
      punycode = "xn--lgrd-poac.no"
    )
    testDecode(
      unicode = "個人.香港",
      punycode = "xn--gmqw5a.xn--j6w193g"
    )
    testDecode(
      unicode = "упр.срб",
      punycode = "xn--o1ach.xn--90a3ac"
    )
  }

  /** https://datatracker.ietf.org/doc/html/rfc3492#section-7.1 */
  @Test
  fun rfc3492Samples() {
    // (A) Arabic (Egyptian)
    testDecode(
      unicode = "ليهمابتكلموشعربي؟",
      punycode = "xn--egbpdaj6bu4bxfgehfvwxn"
    )

    // (B) Chinese (simplified)
    testDecode(
      unicode = "他们为什么不说中文",
      punycode = "xn--ihqwcrb4cv8a8dqg056pqjye"
    )

    // (C) Chinese (traditional)
    testDecode(
      unicode = "他們爲什麽不說中文",
      punycode = "xn--ihqwctvzc91f659drss3x8bo0yb"
    )

    // (D) Czech
    testDecode(
      unicode = "Pročprostěnemluvíčesky",
      punycode = "xn--Proprostnemluvesky-uyb24dma41a"
    )

    // (E) Hebrew:
    testDecode(
      unicode = "למההםפשוטלאמדבריםעברית",
      punycode = "xn--4dbcagdahymbxekheh6e0a7fei0b"
    )

    // (F) Hindi (Devanagari)
    testDecode(
      unicode = "यहलोगहिन्दीक्योंनहींबोलसकतेहैं",
      punycode = "xn--i1baa7eci9glrd9b2ae1bj0hfcgg6iyaf8o0a1dig0cd"
    )

    // (G) Japanese (kanji and hiragana)
    testDecode(
      unicode = "なぜみんな日本語を話してくれないのか",
      punycode = "xn--n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa"
    )

    // (H) Korean (Hangul syllables)
    testDecode(
      unicode = "세계의모든사람들이한국어를이해한다면얼마나좋을까",
      punycode = "xn--989aomsvi5e83db1d2a355cv1e0vak1dwrv93d5xbh15a0dt30a5jpsd879ccm6fea98c"
    )

    // (I) Russian (Cyrillic)
    testDecode(
      unicode = "почемужеонинеговорятпорусски",
      punycode = "xn--b1abfaaepdrnnbgefbaDotcwatmq2g4l"
    )

    // (J) Spanish
    testDecode(
      unicode = "PorquénopuedensimplementehablarenEspañol",
      punycode = "xn--PorqunopuedensimplementehablarenEspaol-fmd56a"
    )

    // (K) Vietnamese
    testDecode(
      unicode = "TạisaohọkhôngthểchỉnóitiếngViệt",
      punycode = "xn--TisaohkhngthchnitingVit-kjcr8268qyxafd2f1b9g"
    )
  }

  private fun testDecode(unicode: String, punycode: String) {
    assertEquals(unicode, Punycode.decode(punycode))
  }
}
