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
package okhttp3.logging

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import okhttp3.logging.internal.isProbablyUtf8
import okio.Buffer
import org.junit.jupiter.api.Test

class ProbablyUtf8Test {
  @Test fun readProbablyUtf8String() {
    assertRead(Buffer(), "")
    assertRead(Buffer().writeUtf8("abc"), "abc")
    assertRead(Buffer().writeUtf8("new\r\nlines"), "new\r\nlines")
    assertRead(Buffer().writeUtf8("white\t space"), "white\t space")
    assertRead(Buffer().writeByte(0x80), String(byteArrayOf(0x80.toByte())))
    assertRead(Buffer().writeByte(0xc0), String(byteArrayOf(0xc0.toByte())))
    assertRead(Buffer().writeUtf8("a").writeByte(0x00).writeUtf8("bc"), "a")
  }

  private fun assertRead(expected: Buffer, actual: String) {
    val builder = StringBuilder()
    expected.readProbablyUtf8String(builder, Charsets.UTF_8)
    assertEquals(builder.toString(), actual)
  }
}
