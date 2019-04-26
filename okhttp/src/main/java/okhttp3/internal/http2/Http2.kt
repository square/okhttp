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
package okhttp3.internal.http2;

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

  /** Lookup table for valid frame types. */
  private static final String[] FRAME_NAMES = new String[] {
      "DATA",
      "HEADERS",
      "PRIORITY",
      "RST_STREAM",
      "SETTINGS",
      "PUSH_PROMISE",
      "PING",
      "GOAWAY",
      "WINDOW_UPDATE",
      "CONTINUATION"
  };

  /**
   * Lookup table for valid flags for DATA, HEADERS, CONTINUATION. Invalid combinations are
   * represented in binary.
   */
  static final String[] FLAGS = new String[0x40]; // Highest bit flag is 0x20.
  static final String[] BINARY = new String[256];
  static {
    for (int i = 0; i < BINARY.length; i++) {
      BINARY[i] = format("%8s", Integer.toBinaryString(i)).replace(' ', '0');
    }

    FLAGS[FLAG_NONE] = "";
    FLAGS[FLAG_END_STREAM] = "END_STREAM";

    int[] prefixFlags = new int[] {FLAG_END_STREAM};

    FLAGS[FLAG_PADDED] = "PADDED";
    for (int prefixFlag : prefixFlags) {
      FLAGS[prefixFlag | FLAG_PADDED] = FLAGS[prefixFlag] + "|PADDED";
    }

    FLAGS[FLAG_END_HEADERS] = "END_HEADERS"; // Same as END_PUSH_PROMISE.
    FLAGS[FLAG_PRIORITY] = "PRIORITY"; // Same as FLAG_COMPRESSED.
    FLAGS[FLAG_END_HEADERS | FLAG_PRIORITY] = "END_HEADERS|PRIORITY"; // Only valid on HEADERS.
    int[] frameFlags = new int[] {
        FLAG_END_HEADERS, FLAG_PRIORITY, FLAG_END_HEADERS | FLAG_PRIORITY
    };

    for (int frameFlag : frameFlags) {
      for (int prefixFlag : prefixFlags) {
        FLAGS[prefixFlag | frameFlag] = FLAGS[prefixFlag] + '|' + FLAGS[frameFlag];
        FLAGS[prefixFlag | frameFlag | FLAG_PADDED]
            = FLAGS[prefixFlag] + '|' + FLAGS[frameFlag] + "|PADDED";
      }
    }

    for (int i = 0; i < FLAGS.length; i++) { // Fill in holes with binary representation.
      if (FLAGS[i] == null) FLAGS[i] = BINARY[i];
    }
  }

  private Http2() {
  }

  static IllegalArgumentException illegalArgument(String message, Object... args) {
    throw new IllegalArgumentException(format(message, args));
  }

  static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(format(message, args));
  }

  /**
   * Returns human-readable representation of HTTP/2 frame headers.
   *
   * <p>The format is:
   *
   * <pre>
   *   direction streamID length type flags
   * </pre>
   *
   * Where direction is {@code <<} for inbound and {@code >>} for outbound.
   *
   * <p>For example, the following would indicate a HEAD request sent from the client.
   * <pre>
   * {@code
   *   << 0x0000000f    12 HEADERS       END_HEADERS|END_STREAM
   * }
   * </pre>
   */
  static String frameLog(boolean inbound, int streamId, int length, byte type, byte flags) {
    String formattedType = type < FRAME_NAMES.length ? FRAME_NAMES[type] : format("0x%02x", type);
    String formattedFlags = formatFlags(type, flags);
    return format("%s 0x%08x %5d %-13s %s", inbound ? "<<" : ">>", streamId, length,
        formattedType, formattedFlags);
  }

  /**
   * Looks up valid string representing flags from the table. Invalid combinations are represented
   * in binary.
   */
  // Visible for testing.
  static String formatFlags(byte type, byte flags) {
    if (flags == 0) return "";
    switch (type) { // Special case types that have 0 or 1 flag.
      case TYPE_SETTINGS:
      case TYPE_PING:
        return flags == FLAG_ACK ? "ACK" : BINARY[flags];
      case TYPE_PRIORITY:
      case TYPE_RST_STREAM:
      case TYPE_GOAWAY:
      case TYPE_WINDOW_UPDATE:
        return BINARY[flags];
    }
    String result = flags < FLAGS.length ? FLAGS[flags] : BINARY[flags];
    // Special case types that have overlap flag values.
    if (type == TYPE_PUSH_PROMISE && (flags & FLAG_END_PUSH_PROMISE) != 0) {
      return result.replace("HEADERS", "PUSH_PROMISE"); // TODO: Avoid allocation.
    } else if (type == TYPE_DATA && (flags & FLAG_COMPRESSED) != 0) {
      return result.replace("PRIORITY", "COMPRESSED"); // TODO: Avoid allocation.
    }
    return result;
  }
}
