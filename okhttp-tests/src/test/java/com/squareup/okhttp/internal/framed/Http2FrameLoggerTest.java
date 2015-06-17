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
package com.squareup.okhttp.internal.framed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.framed.Http2.FLAG_ACK;
import static com.squareup.okhttp.internal.framed.Http2.FLAG_END_HEADERS;
import static com.squareup.okhttp.internal.framed.Http2.FLAG_END_STREAM;
import static com.squareup.okhttp.internal.framed.Http2.FLAG_NONE;
import static com.squareup.okhttp.internal.framed.Http2.FrameLogger.formatFlags;
import static com.squareup.okhttp.internal.framed.Http2.FrameLogger.formatHeader;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_CONTINUATION;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_DATA;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_GOAWAY;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_HEADERS;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_PING;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_PUSH_PROMISE;
import static com.squareup.okhttp.internal.framed.Http2.TYPE_SETTINGS;
import static org.junit.Assert.assertEquals;

public class Http2FrameLoggerTest {

  /** Real stream traffic applied to the log format. */
  @Test public void exampleStream() {
    assertEquals(">> 0x00000000     5 SETTINGS      ",
        formatHeader(false, 0, 5, TYPE_SETTINGS, FLAG_NONE));
    assertEquals(">> 0x00000003   100 HEADERS       END_HEADERS",
        formatHeader(false, 3, 100, TYPE_HEADERS, FLAG_END_HEADERS));
    assertEquals(">> 0x00000003     0 DATA          END_STREAM",
        formatHeader(false, 3, 0, TYPE_DATA, FLAG_END_STREAM));
    assertEquals("<< 0x00000000    15 SETTINGS      ",
        formatHeader(true, 0, 15, TYPE_SETTINGS, FLAG_NONE));
    assertEquals(">> 0x00000000     0 SETTINGS      ACK",
        formatHeader(false, 0, 0, TYPE_SETTINGS, FLAG_ACK));
    assertEquals("<< 0x00000000     0 SETTINGS      ACK",
        formatHeader(true, 0, 0, TYPE_SETTINGS, FLAG_ACK));
    assertEquals("<< 0x00000003    22 HEADERS       END_HEADERS",
        formatHeader(true, 3, 22, TYPE_HEADERS, FLAG_END_HEADERS));
    assertEquals("<< 0x00000003   226 DATA          END_STREAM",
        formatHeader(true, 3, 226, TYPE_DATA, FLAG_END_STREAM));
    assertEquals(">> 0x00000000     8 GOAWAY        ",
        formatHeader(false, 0, 8, TYPE_GOAWAY, FLAG_NONE));
  }

  @Test public void flagOverlapOn0x1() {
    assertEquals("<< 0x00000000     0 SETTINGS      ACK",
        formatHeader(true, 0, 0, TYPE_SETTINGS, (byte) 0x1));
    assertEquals("<< 0x00000000     8 PING          ACK",
        formatHeader(true, 0, 8, TYPE_PING, (byte) 0x1));
    assertEquals("<< 0x00000003     0 HEADERS       END_STREAM",
        formatHeader(true, 3, 0, TYPE_HEADERS, (byte) 0x1));
    assertEquals("<< 0x00000003     0 DATA          END_STREAM",
        formatHeader(true, 3, 0, TYPE_DATA, (byte) 0x1));
  }

  @Test public void flagOverlapOn0x4() {
    assertEquals("<< 0x00000003 10000 HEADERS       END_HEADERS",
        formatHeader(true, 3, 10000, TYPE_HEADERS, (byte) 0x4));
    assertEquals("<< 0x00000003 10000 CONTINUATION  END_HEADERS",
        formatHeader(true, 3, 10000, TYPE_CONTINUATION, (byte) 0x4));
    assertEquals("<< 0x00000004 10000 PUSH_PROMISE  END_PUSH_PROMISE",
        formatHeader(true, 4, 10000, TYPE_PUSH_PROMISE, (byte) 0x4));
  }

  @Test public void flagOverlapOn0x20() {
    assertEquals("<< 0x00000003 10000 HEADERS       PRIORITY",
        formatHeader(true, 3, 10000, TYPE_HEADERS, (byte) 0x20));
    assertEquals("<< 0x00000003 10000 DATA          COMPRESSED",
        formatHeader(true, 3, 10000, TYPE_DATA, (byte) 0x20));
  }

  /**
   * Ensures that valid flag combinations appear visually correct, and invalid show in hex.  This
   * also demonstrates how sparse the lookup table is.
   */
  @Test public void allFormattedFlagsWithValidBits() {
    List<String> formattedFlags = new ArrayList<>(0x40); // Highest valid flag is 0x20.
    for (byte i = 0; i < 0x40; i++) formattedFlags.add(formatFlags(TYPE_HEADERS, i));

    assertEquals(Arrays.asList(
        "",
        "END_STREAM",
        "00000010",
        "00000011",
        "END_HEADERS",
        "END_STREAM|END_HEADERS",
        "00000110",
        "00000111",
        "PADDED",
        "END_STREAM|PADDED",
        "00001010",
        "00001011",
        "00001100",
        "END_STREAM|END_HEADERS|PADDED",
        "00001110",
        "00001111",
        "00010000",
        "00010001",
        "00010010",
        "00010011",
        "00010100",
        "00010101",
        "00010110",
        "00010111",
        "00011000",
        "00011001",
        "00011010",
        "00011011",
        "00011100",
        "00011101",
        "00011110",
        "00011111",
        "PRIORITY",
        "END_STREAM|PRIORITY",
        "00100010",
        "00100011",
        "END_HEADERS|PRIORITY",
        "END_STREAM|END_HEADERS|PRIORITY",
        "00100110",
        "00100111",
        "00101000",
        "END_STREAM|PRIORITY|PADDED",
        "00101010",
        "00101011",
        "00101100",
        "END_STREAM|END_HEADERS|PRIORITY|PADDED",
        "00101110",
        "00101111",
        "00110000",
        "00110001",
        "00110010",
        "00110011",
        "00110100",
        "00110101",
        "00110110",
        "00110111",
        "00111000",
        "00111001",
        "00111010",
        "00111011",
        "00111100",
        "00111101",
        "00111110",
        "00111111"
    ), formattedFlags);
  }
}
