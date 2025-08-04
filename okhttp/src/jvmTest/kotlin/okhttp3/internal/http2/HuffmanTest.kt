/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http2

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.Random
import okhttp3.internal.http2.Huffman.decode
import okhttp3.internal.http2.Huffman.encode
import okhttp3.internal.http2.Huffman.encodedLength
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Original version of this class was lifted from `com.twitter.hpack.HuffmanTest`.  */
class HuffmanTest {
  @Test
  fun roundTripForRequestAndResponse() {
    val s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    for (i in s.indices) {
      assertRoundTrip(s.substring(0, i).encodeUtf8())
    }
    val random = Random(123456789L)
    val buf = ByteArray(4096)
    random.nextBytes(buf)
    assertRoundTrip(buf.toByteString())
  }

  private fun assertRoundTrip(data: ByteString) {
    val encodeBuffer = Buffer()
    encode(data, encodeBuffer)
    assertThat(encodedLength(data).toLong()).isEqualTo(encodeBuffer.size)
    val decodeBuffer = Buffer()
    decode(encodeBuffer, encodeBuffer.size, decodeBuffer)
    assertEquals(data, decodeBuffer.readByteString())
  }
}
