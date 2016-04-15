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
package okhttp3.internal.framed;

import okio.ByteString;

/** HTTP header: the name is an ASCII string, but the value can be UTF-8. */
public final class Header {
  // Special header names defined in the SPDY and HTTP/2 specs.
  public static final ByteString RESPONSE_STATUS = ByteString.encodeUtf8(":status");
  public static final ByteString TARGET_METHOD = ByteString.encodeUtf8(":method");
  public static final ByteString TARGET_PATH = ByteString.encodeUtf8(":path");
  public static final ByteString TARGET_SCHEME = ByteString.encodeUtf8(":scheme");
  public static final ByteString TARGET_AUTHORITY = ByteString.encodeUtf8(":authority"); // HTTP/2
  public static final ByteString TARGET_HOST = ByteString.encodeUtf8(":host"); // spdy/3
  public static final ByteString VERSION = ByteString.encodeUtf8(":version"); // spdy/3

  /** Name in case-insensitive ASCII encoding. */
  public final ByteString name;
  /** Value in UTF-8 encoding. */
  public final ByteString value;
  final int hpackSize;

  // TODO: search for toLowerCase and consider moving logic here.
  public Header(String name, String value) {
    this(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value));
  }

  public Header(ByteString name, String value) {
    this(name, ByteString.encodeUtf8(value));
  }

  public Header(ByteString name, ByteString value) {
    this.name = name;
    this.value = value;
    this.hpackSize = 32 + name.size() + value.size();
  }

  @Override public boolean equals(Object other) {
    if (other instanceof Header) {
      Header that = (Header) other;
      return this.name.equals(that.name)
          && this.value.equals(that.value);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + name.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }

  @Override public String toString() {
    return String.format("%s: %s", name.utf8(), value.utf8());
  }
}
