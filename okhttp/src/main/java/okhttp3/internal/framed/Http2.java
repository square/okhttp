/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.IOException;
import okio.ByteString;

import static okhttp3.internal.Util.format;

public final class Http2 {
  static final ByteString CONNECTION_PREFACE
      = ByteString.encodeUtf8("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

  /** The initial max frame size, applied independently writing to, or reading from the peer. */
  static final int INITIAL_MAX_FRAME_SIZE = 0x4000; // 16384

  static final byte TYPE_DATA = 0x0;
  static final byte TYPE_HEADERS = 0x1;
  static final byte TYPE_PRIORITY = 0x2;
  static final byte TYPE_RST_STREAM = 0x3;
  static final byte TYPE_SETTINGS = 0x4;
  static final byte TYPE_PUSH_PROMISE = 0x5;
  static final byte TYPE_PING = 0x6;
  static final byte TYPE_GOAWAY = 0x7;
  static final byte TYPE_WINDOW_UPDATE = 0x8;
  static final byte TYPE_CONTINUATION = 0x9;

  static final byte FLAG_NONE = 0x0;
  static final byte FLAG_ACK = 0x1; // Used for settings and ping.
  static final byte FLAG_END_STREAM = 0x1; // Used for headers and data.
  static final byte FLAG_END_HEADERS = 0x4; // Used for headers and continuation.
  static final byte FLAG_END_PUSH_PROMISE = 0x4;
  static final byte FLAG_PADDED = 0x8; // Used for headers and data.
  static final byte FLAG_PRIORITY = 0x20; // Used for headers.
  static final byte FLAG_COMPRESSED = 0x20; // Used for data.

  private Http2() {
  }

  static IllegalArgumentException illegalArgument(String message, Object... args) {
    throw new IllegalArgumentException(format(message, args));
  }

  static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(format(message, args));
  }
}
