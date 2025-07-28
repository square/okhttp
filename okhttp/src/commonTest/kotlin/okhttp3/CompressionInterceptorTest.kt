/*
 * Copyright (c) 2025 Block, Inc.
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
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import okhttp3.CompressionInterceptor.Companion.Gzip
import okhttp3.CompressionInterceptor.Companion.Identity
import okhttp3.CompressionInterceptor.Companion.Wildcard
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import org.junit.jupiter.api.Test

class CompressionInterceptorTest {
  val source =
    Buffer().apply {
      write("Hello World".encodeUtf8())
    } as Source

  @Test
  fun emptyDefaultsToIdentity() {
    val empty = CompressionInterceptor()

    assertThat(empty.acceptEncoding).isEqualTo("identity")
  }

  @Test
  fun identityIsIdentity() {
    val identity = CompressionInterceptor(Identity)

    assertThat(identity.acceptEncoding).isEqualTo("identity")
    assertThat(identity.lookupDecompressor("gzip")).isNull()
    assertThat(identity.lookupDecompressor("identity")).isNull()
  }

  @Test
  fun wildcardDoesNotDecompress() {
    val identity = CompressionInterceptor(Wildcard)

    assertThat(identity.acceptEncoding).isEqualTo("*")
    assertThat(identity.lookupDecompressor("gzip")).isNull()
    assertThat(identity.lookupDecompressor("identity")).isNull()
  }

  @Test
  fun gzipIsSupported() {
    val gzip = CompressionInterceptor(Gzip)

    assertThat(gzip.acceptEncoding).isEqualTo("gzip")
    assertThat(gzip.lookupDecompressor("gzip")).isSameInstanceAs(Gzip)
    assertThat(gzip.lookupDecompressor("br")).isNull()
  }

  @Test
  fun prioritiesAreSupported() {
    val complex = CompressionInterceptor(linkedMapOf(Gzip to 1.0, Identity to null, Wildcard to 0.3))

    assertThat(complex.acceptEncoding).isEqualTo("gzip;q=1.0, identity, *;q=0.3")
  }
}
