/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3.zstd

import com.squareup.zstd.okio.zstdDecompress
import okhttp3.CompressionInterceptor
import okio.BufferedSource
import okio.Source

/**
 * Support for Zstandard encoding. Use via the [CompressionInterceptor].
 */
object Zstd : CompressionInterceptor.DecompressionAlgorithm {
  override val encoding: String get() = "zstd"

  override fun decompress(compressedSource: BufferedSource): Source = compressedSource.zstdDecompress()
}
