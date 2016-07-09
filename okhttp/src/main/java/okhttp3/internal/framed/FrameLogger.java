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

import static okhttp3.internal.Util.format;
import static okhttp3.internal.framed.Http2.FLAG_ACK;
import static okhttp3.internal.framed.Http2.FLAG_COMPRESSED;
import static okhttp3.internal.framed.Http2.FLAG_END_HEADERS;
import static okhttp3.internal.framed.Http2.FLAG_END_PUSH_PROMISE;
import static okhttp3.internal.framed.Http2.FLAG_END_STREAM;
import static okhttp3.internal.framed.Http2.FLAG_NONE;
import static okhttp3.internal.framed.Http2.FLAG_PADDED;
import static okhttp3.internal.framed.Http2.FLAG_PRIORITY;
import static okhttp3.internal.framed.Http2.TYPE_DATA;
import static okhttp3.internal.framed.Http2.TYPE_GOAWAY;
import static okhttp3.internal.framed.Http2.TYPE_PING;
import static okhttp3.internal.framed.Http2.TYPE_PRIORITY;
import static okhttp3.internal.framed.Http2.TYPE_PUSH_PROMISE;
import static okhttp3.internal.framed.Http2.TYPE_RST_STREAM;
import static okhttp3.internal.framed.Http2.TYPE_SETTINGS;
import static okhttp3.internal.framed.Http2.TYPE_WINDOW_UPDATE;

/**
 * Logs a human-readable representation of HTTP/2 frame headers.
 *
 * <p>The format is:
 *
 * <pre>
 *   direction streamID length type flags
 * </pre>
 * Where direction is {@code <<} for inbound and {@code >>} for outbound.
 *
 * <p>For example, the following would indicate a HEAD request sent from the client.
 * <pre>
 * {@code
 *   << 0x0000000f    12 HEADERS       END_HEADERS|END_STREAM
 * }
 * </pre>
 */
final class FrameLogger {
  private FrameLogger() {
  }

  static String formatHeader(boolean inbound, int streamId, int length, byte type, byte flags) {
    String formattedType = type < TYPES.length ? TYPES[type] : format("0x%02x", type);
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

  /** Lookup table for valid frame types. */
  private static final String[] TYPES = new String[] {
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
  private static final String[] FLAGS = new String[0x40]; // Highest bit flag is 0x20.
  private static final String[] BINARY = new String[256];

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
}
