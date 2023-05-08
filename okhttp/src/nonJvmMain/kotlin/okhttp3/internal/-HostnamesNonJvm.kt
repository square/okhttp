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

import com.squareup.okhttpicu.SYSTEM_NORMALIZER
import okhttp3.internal.idn.IDNA_MAPPING_TABLE
import okhttp3.internal.idn.Punycode
import okio.Buffer

internal actual fun idnToAscii(host: String): String? {
  val bufferA = Buffer().writeUtf8(host)
  val bufferB = Buffer()

  // 1. Map, from bufferA to bufferB.
  while (!bufferA.exhausted()) {
    val codePoint = bufferA.readUtf8CodePoint()
    if(!IDNA_MAPPING_TABLE.map(codePoint, bufferB)) return null
  }

  // 2. Normalize, from bufferB to bufferA.
  val normalized = SYSTEM_NORMALIZER.normalizeNfc(bufferB.readUtf8())
  bufferA.writeUtf8(normalized)

  // 3. For each label, convert/validate Punycode.
  val decoded = Punycode.decode(bufferA.readUtf8())
  // TODO: check 4.1 Validity Criteria
  return Punycode.encode(decoded)
}
