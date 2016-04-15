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

import java.io.IOException;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.ByteString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class Spdy3Test {
  static final int expectedStreamId = 15;

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new Buffer().write(new byte[0x1000000]));
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
    Buffer frame = new Buffer();

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    // Compose the expected GOAWAY frame without debug data
    // |C| Version(15bits) | Type(16bits) |
    frame.writeInt(0x80000000 | (Spdy3.VERSION & 0x7fff) << 16 | Spdy3.TYPE_GOAWAY & 0xffff);
    // | Flags (8)  |  Length (24 bits)   |
    frame.writeInt(8); // no flags and length is 8.
    frame.writeInt(expectedStreamId); // last good stream.
    frame.writeInt(expectedError.spdyGoAwayCode);

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    // SPDY/3 does not send debug data, so bytes should be same!
    assertEquals(frame, sendGoAway(expectedStreamId, expectedError, new byte[8]));

    FrameReader fr = new Spdy3.Reader(frame, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the goAway frame.
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(expectedStreamId, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(0, debugData.size());
      }
    });
  }

  private void sendDataFrame(Buffer source) throws IOException {
    Spdy3.Writer writer = new Spdy3.Writer(new Buffer(), true);
    writer.sendDataFrame(expectedStreamId, 0, source, (int) source.size());
  }

  private void windowUpdate(long increment) throws IOException {
    new Spdy3.Writer(new Buffer(), true).windowUpdate(expectedStreamId, increment);
  }

  private Buffer sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    Buffer out = new Buffer();
    new Spdy3.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out;
  }
}
