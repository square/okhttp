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
package com.squareup.okhttp.internal.spdy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_ACK;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_END_HEADERS;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_END_STREAM;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_NONE;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FrameLogger.formatFlags;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FrameLogger.formatHeader;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.TYPE_DATA;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.TYPE_GOAWAY;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.TYPE_HEADERS;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.TYPE_SETTINGS;
import static org.junit.Assert.assertEquals;

public class Http20Draft10FrameLoggerTest {

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

  /**
   * Ensures that valid flag combinations appear visually correct, and invalid show in hex.  This
   * also demonstrates how sparse the lookup table is.
   */
  @Test public void allFormattedFlagsWithValidBits() {
    List<String> formattedFlags = new ArrayList<String>(0x40); // Highest valid flag is 0x20.
    for (byte i = 0; i < 0x40; i++) formattedFlags.add(formatFlags(TYPE_HEADERS, i));

    assertEquals(Arrays.asList(
        "",
        "END_STREAM",
        "END_SEGMENT",
        "END_STREAM|END_SEGMENT",
        "END_HEADERS",
        "END_STREAM|END_HEADERS",
        "END_SEGMENT|END_HEADERS",
        "END_STREAM|END_SEGMENT|END_HEADERS",
        "PRIORITY",
        "END_STREAM|PRIORITY",
        "END_SEGMENT|PRIORITY",
        "END_STREAM|END_SEGMENT|PRIORITY",
        "END_HEADERS|PRIORITY",
        "END_STREAM|END_HEADERS|PRIORITY",
        "END_SEGMENT|END_HEADERS|PRIORITY",
        "END_STREAM|END_SEGMENT|END_HEADERS|PRIORITY",
        "PAD_LOW",
        "END_STREAM|PAD_LOW",
        "END_SEGMENT|PAD_LOW",
        "END_STREAM|END_SEGMENT|PAD_LOW",
        "00010100",
        "END_STREAM|END_HEADERS|PAD_LOW",
        "END_SEGMENT|END_HEADERS|PAD_LOW",
        "END_STREAM|END_SEGMENT|END_HEADERS|PAD_LOW",
        "00011000",
        "END_STREAM|PRIORITY|PAD_LOW",
        "END_SEGMENT|PRIORITY|PAD_LOW",
        "END_STREAM|END_SEGMENT|PRIORITY|PAD_LOW",
        "00011100",
        "END_STREAM|END_HEADERS|PRIORITY|PAD_LOW",
        "END_SEGMENT|END_HEADERS|PRIORITY|PAD_LOW",
        "END_STREAM|END_SEGMENT|END_HEADERS|PRIORITY|PAD_LOW",
        "00100000",
        "00100001",
        "00100010",
        "00100011",
        "00100100",
        "00100101",
        "00100110",
        "00100111",
        "00101000",
        "00101001",
        "00101010",
        "00101011",
        "00101100",
        "00101101",
        "00101110",
        "00101111",
        "PAD_LOW|PAD_HIGH",
        "END_STREAM|PAD_LOW|PAD_HIGH",
        "END_SEGMENT|PAD_LOW|PAD_HIGH",
        "END_STREAM|END_SEGMENT|PAD_LOW|PAD_HIGH",
        "00110100",
        "END_STREAM|END_HEADERS|PAD_LOW|PAD_HIGH",
        "END_SEGMENT|END_HEADERS|PAD_LOW|PAD_HIGH",
        "END_STREAM|END_SEGMENT|END_HEADERS|PAD_LOW|PAD_HIGH",
        "00111000",
        "END_STREAM|PRIORITY|PAD_LOW|PAD_HIGH",
        "END_SEGMENT|PRIORITY|PAD_LOW|PAD_HIGH",
        "END_STREAM|END_SEGMENT|PRIORITY|PAD_LOW|PAD_HIGH",
        "00111100",
        "END_STREAM|END_HEADERS|PRIORITY|PAD_LOW|PAD_HIGH",
        "END_SEGMENT|END_HEADERS|PRIORITY|PAD_LOW|PAD_HIGH",
        "END_STREAM|END_SEGMENT|END_HEADERS|PRIORITY|PAD_LOW|PAD_HIGH"
    ), formattedFlags);
  }
}
