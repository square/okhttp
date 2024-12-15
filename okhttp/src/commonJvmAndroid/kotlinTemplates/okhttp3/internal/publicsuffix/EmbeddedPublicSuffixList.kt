/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.publicsuffix

//Note that PublicSuffixDatabase.gz is compiled from The Public Suffix List:
//https://publicsuffix.org/list/public_suffix_list.dat
//
//It is subject to the terms of the Mozilla Public License, v. 2.0:
//https://mozilla.org/MPL/2.0/

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.GzipSource
import okio.buffer

/**
 * A implementation of I/O for PublicSuffixDatabase.gz by directly encoding
 * the relevant byte arrays in a class file.
 */
internal object EmbeddedPublicSuffixList: PublicSuffixList {
  override fun ensureLoaded() {
  }

  override val bytes: ByteString

  override val exceptionBytes: ByteString

  init {
    Buffer().use { buffer ->
      buffer.write($publicSuffixListBytes)
      GzipSource(buffer).buffer().use { source ->
        val totalBytes = source.readInt()
        bytes = source.readByteString(totalBytes.toLong())

        val totalExceptionBytes = source.readInt()
        exceptionBytes = source.readByteString(totalExceptionBytes.toLong())
      }
    }
  }
}
