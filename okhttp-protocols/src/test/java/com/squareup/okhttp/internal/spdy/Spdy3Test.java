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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class Spdy3Test {
  static final int expectedStreamId = 15;

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new byte[0x1000000]);
      fail();
    } catch (IOException e) {
      assertEquals("FRAME_TOO_LARGE max size is 16Mib: 16777216", e.getMessage());
    }
  }

  private byte[] sendDataFrame(byte[] data) throws IOException {
    return sendDataFrame(data, 0, data.length);
  }

  private byte[] sendDataFrame(byte[] data, int offset, int byteCount) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Spdy3.Writer(out, true).sendDataFrame(expectedStreamId, 0, data, offset, byteCount);
    return out.toByteArray();
  }
}
