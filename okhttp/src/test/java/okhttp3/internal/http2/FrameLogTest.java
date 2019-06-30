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
package okhttp3.internal.http2;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static okhttp3.internal.http2.Http2.FLAG_ACK;
import static okhttp3.internal.http2.Http2.FLAG_END_HEADERS;
import static okhttp3.internal.http2.Http2.FLAG_END_STREAM;
import static okhttp3.internal.http2.Http2.FLAG_NONE;
import static okhttp3.internal.http2.Http2.TYPE_CONTINUATION;
import static okhttp3.internal.http2.Http2.TYPE_DATA;
import static okhttp3.internal.http2.Http2.TYPE_GOAWAY;
import static okhttp3.internal.http2.Http2.TYPE_HEADERS;
import static okhttp3.internal.http2.Http2.TYPE_PING;
import static okhttp3.internal.http2.Http2.TYPE_PUSH_PROMISE;
import static okhttp3.internal.http2.Http2.TYPE_SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;

public final class FrameLogTest {
  /** Real stream traffic applied to the log format. */
  @Test public void exampleStream() {
    assertThat(frameLog(false, 0, 5, TYPE_SETTINGS, FLAG_NONE)).isEqualTo(
        ">> 0x00000000     5 SETTINGS      ");
    assertThat(frameLog(false, 3, 100, TYPE_HEADERS, FLAG_END_HEADERS)).isEqualTo(
        ">> 0x00000003   100 HEADERS       END_HEADERS");
    assertThat(frameLog(false, 3, 0, TYPE_DATA, FLAG_END_STREAM)).isEqualTo(
        ">> 0x00000003     0 DATA          END_STREAM");
    assertThat(frameLog(true, 0, 15, TYPE_SETTINGS, FLAG_NONE)).isEqualTo(
        "<< 0x00000000    15 SETTINGS      ");
    assertThat(frameLog(false, 0, 0, TYPE_SETTINGS, FLAG_ACK)).isEqualTo(
        ">> 0x00000000     0 SETTINGS      ACK");
    assertThat(frameLog(true, 0, 0, TYPE_SETTINGS, FLAG_ACK)).isEqualTo(
        "<< 0x00000000     0 SETTINGS      ACK");
    assertThat(frameLog(true, 3, 22, TYPE_HEADERS, FLAG_END_HEADERS)).isEqualTo(
        "<< 0x00000003    22 HEADERS       END_HEADERS");
    assertThat(frameLog(true, 3, 226, TYPE_DATA, FLAG_END_STREAM)).isEqualTo(
        "<< 0x00000003   226 DATA          END_STREAM");
    assertThat(frameLog(false, 0, 8, TYPE_GOAWAY, FLAG_NONE)).isEqualTo(
        ">> 0x00000000     8 GOAWAY        ");
  }

  @Test public void flagOverlapOn0x1() {
    assertThat(frameLog(true, 0, 0, TYPE_SETTINGS, (byte) 0x1)).isEqualTo(
        "<< 0x00000000     0 SETTINGS      ACK");
    assertThat(frameLog(true, 0, 8, TYPE_PING, (byte) 0x1)).isEqualTo(
        "<< 0x00000000     8 PING          ACK");
    assertThat(frameLog(true, 3, 0, TYPE_HEADERS, (byte) 0x1)).isEqualTo(
        "<< 0x00000003     0 HEADERS       END_STREAM");
    assertThat(frameLog(true, 3, 0, TYPE_DATA, (byte) 0x1)).isEqualTo(
        "<< 0x00000003     0 DATA          END_STREAM");
  }

  @Test public void flagOverlapOn0x4() {
    assertThat(frameLog(true, 3, 10000, TYPE_HEADERS, (byte) 0x4)).isEqualTo(
        "<< 0x00000003 10000 HEADERS       END_HEADERS");
    assertThat(frameLog(true, 3, 10000, TYPE_CONTINUATION, (byte) 0x4)).isEqualTo(
        "<< 0x00000003 10000 CONTINUATION  END_HEADERS");
    assertThat(frameLog(true, 4, 10000, TYPE_PUSH_PROMISE, (byte) 0x4)).isEqualTo(
        "<< 0x00000004 10000 PUSH_PROMISE  END_PUSH_PROMISE");
  }

  @Test public void flagOverlapOn0x20() {
    assertThat(frameLog(true, 3, 10000, TYPE_HEADERS, (byte) 0x20)).isEqualTo(
        "<< 0x00000003 10000 HEADERS       PRIORITY");
    assertThat(frameLog(true, 3, 10000, TYPE_DATA, (byte) 0x20)).isEqualTo(
        "<< 0x00000003 10000 DATA          COMPRESSED");
  }

  /**
   * Ensures that valid flag combinations appear visually correct, and invalid show in hex.  This
   * also demonstrates how sparse the lookup table is.
   */
  @Test public void allFormattedFlagsWithValidBits() {
    List<String> formattedFlags = new ArrayList<>(0x40); // Highest valid flag is 0x20.
    for (byte i = 0; i < 0x40; i++) formattedFlags.add(Http2.INSTANCE.formatFlags(TYPE_HEADERS, i));

    assertThat(formattedFlags).containsExactly(
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
    );
  }

  private String frameLog(boolean inbound, int streamId, int length, int type, int flags) {
    return Http2.INSTANCE.frameLog(inbound, streamId, length, type, flags);
  }
}
