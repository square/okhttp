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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.BufferedSource;
import com.squareup.okhttp.internal.bytes.ByteString;
import com.squareup.okhttp.internal.bytes.OkBuffer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class Spdy3Test {
  static final int expectedStreamId = 15;

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new byte[0x1000000]);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_TOO_LARGE max size is 16Mib: " + 0x1000000L, e.getMessage());
    }
  }

  @Test public void badWindowSizeIncrement() throws IOException {
    try {
      windowUpdate(0);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("windowSizeIncrement must be between 1 and 0x7fffffff: 0", e.getMessage());
    }
    try {
      windowUpdate(0x80000000L);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("windowSizeIncrement must be between 1 and 0x7fffffff: 2147483648",
          e.getMessage());
    }
  }

  @Test public void goAwayRoundTrip() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    // Compose the expected GOAWAY frame without debug data
    // |C| Version(15bits) | Type(16bits) |
    dataOut.writeInt(0x80000000 | (Spdy3.VERSION & 0x7fff) << 16 | Spdy3.TYPE_GOAWAY & 0xffff);
    // | Flags (8)  |  Length (24 bits)   |
    dataOut.writeInt(8); // no flags and length is 8.
    dataOut.writeInt(expectedStreamId); // last good stream.
    dataOut.writeInt(expectedError.spdyGoAwayCode);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(),
        sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    // SPDY/3 does not send debug data, so bytes should be same!
    assertArrayEquals(out.toByteArray(), sendGoAway(expectedStreamId, expectedError, new byte[8]));

    FrameReader fr = newReader(out);

    fr.nextFrame(new BaseTestHandler() { // Consume the goAway frame.
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(expectedStreamId, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(0, debugData.size());
      }
    });
  }

  private Spdy3.Reader newReader(ByteArrayOutputStream out) {
    OkBuffer data = new OkBuffer();
    data.write(ByteString.of(out.toByteArray()));
    return new Spdy3.Reader(new BufferedSource(data), false);
  }

  private byte[] sendDataFrame(byte[] data) throws IOException {
    return sendDataFrame(data, 0, data.length);
  }

  private byte[] sendDataFrame(byte[] data, int offset, int byteCount) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Spdy3.Writer(out, true).sendDataFrame(expectedStreamId, 0, data, offset, byteCount);
    return out.toByteArray();
  }

  private byte[] windowUpdate(long increment) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Spdy3.Writer(out, true).windowUpdate(expectedStreamId, increment);
    return out.toByteArray();
  }

  private byte[] sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Spdy3.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out.toByteArray();
  }
}
