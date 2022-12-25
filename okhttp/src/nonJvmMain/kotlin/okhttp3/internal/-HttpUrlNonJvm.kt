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

import okio.Buffer

internal object NonJvmHttpUrl {

}

internal actual object HttpUrlCommon {
  internal actual fun Buffer.writePercentDecoded(
    encoded: String,
    pos: Int,
    limit: Int,
    plusIsSpace: Boolean
  ) {
    // TODO implement decoding
    writeUtf8(encoded, pos, limit)
  }
  internal actual fun String.canonicalize(
    pos: Int,
    limit: Int,
    encodeSet: String,
    alreadyEncoded: Boolean,
    strict: Boolean,
    plusIsSpace: Boolean,
    unicodeAllowed: Boolean,
  ): String {
    // TODO implement canonicalization
    return this.subSequence(pos, limit).toString()
  }
}
