/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http2

import okhttp3.internal.Util
import okio.ByteString

/** HTTP header: the name is an ASCII string, but the value can be UTF-8. */
class Header(
  /** Name in case-insensitive ASCII encoding. */
  @JvmField val name: ByteString,
  /** Value in UTF-8 encoding. */
  @JvmField val value: ByteString
) {
  @JvmField internal val hpackSize = 32 + name.size() + value.size()

  // TODO: search for toLowerCase and consider moving logic here.
  constructor(name: String, value: String) : this(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value))

  constructor(name: ByteString, value: String) : this(name, ByteString.encodeUtf8(value))

  override fun equals(other: Any?): Boolean {
    return other is Header
        && this.name == other.name
        && this.value == other.value
  }

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + name.hashCode()
    result = 31 * result + value.hashCode()
    return result
  }

  override fun toString(): String {
    return Util.format("%s: %s", name.utf8(), value.utf8())
  }

  companion object {
    // Special header names defined in HTTP/2 spec.
    @JvmField val PSEUDO_PREFIX: ByteString = ByteString.encodeUtf8(":")

    const val RESPONSE_STATUS_UTF8 = ":status"
    const val TARGET_METHOD_UTF8 = ":method"
    const val TARGET_PATH_UTF8 = ":path"
    const val TARGET_SCHEME_UTF8 = ":scheme"
    const val TARGET_AUTHORITY_UTF8 = ":authority"

    @JvmField val RESPONSE_STATUS: ByteString = ByteString.encodeUtf8(RESPONSE_STATUS_UTF8)
    @JvmField val TARGET_METHOD: ByteString = ByteString.encodeUtf8(TARGET_METHOD_UTF8)
    @JvmField val TARGET_PATH: ByteString = ByteString.encodeUtf8(TARGET_PATH_UTF8)
    @JvmField val TARGET_SCHEME: ByteString = ByteString.encodeUtf8(TARGET_SCHEME_UTF8)
    @JvmField val TARGET_AUTHORITY: ByteString = ByteString.encodeUtf8(TARGET_AUTHORITY_UTF8)
  }
}
