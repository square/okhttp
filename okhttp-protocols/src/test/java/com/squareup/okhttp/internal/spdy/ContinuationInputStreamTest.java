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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

import static com.squareup.okhttp.internal.spdy.HpackDraft05Test.MutableByteArrayInputStream;
import static com.squareup.okhttp.internal.spdy.Http20Draft09.ContinuationInputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ContinuationInputStreamTest {
  private final MutableByteArrayInputStream bytesIn = new MutableByteArrayInputStream();
  private final ContinuationInputStream continuation =
      new ContinuationInputStream(new DataInputStream(bytesIn));

  @Test public void read() throws IOException {

    // When there are bytes left this frame, read one.
    continuation.bytesLeft = 2;
    bytesIn.set(new byte[] {1, 2});
    assertEquals(1, continuation.read());
    assertEquals(1, continuation.bytesLeft);

    // When there are bytes left this frame, but none on the remote stream, EOF!
    continuation.bytesLeft = 2;
    bytesIn.set(new byte[] {});
    try {
      continuation.read();
      fail();
    } catch (EOFException expected) {
    }

    // When there are no bytes left in the last header frame, return -1.
    continuation.bytesLeft = 0;
    continuation.endHeaders = true;
    assertEquals(-1, continuation.read());
    assertEquals(0, continuation.bytesLeft);

    // When there are no bytes left in this frame, but it isn't the last, read continuation.
    continuation.bytesLeft = 0;
    continuation.endHeaders = false; // Read continuation.
    bytesIn.set(lastContinuationFrame(new byte[] {1}));
    assertEquals(1, continuation.read());
    assertEquals(0, continuation.bytesLeft);
  }

  @Test public void readArray() throws IOException {
    byte[] buff = new byte[3];

    // When there are bytes left this frame, read them.
    continuation.bytesLeft = 3;
    continuation.endHeaders = true;
    bytesIn.set(new byte[] {1, 2, 3});
    assertEquals(3, continuation.read(buff));
    assertEquals(0, continuation.bytesLeft);
    assertTrue(Arrays.equals(buff, new byte[] {1, 2, 3}));

    // When there are no bytes left in the last header frame, EOF.
    Arrays.fill(buff, (byte) -1);
    continuation.bytesLeft = 0;
    continuation.endHeaders = true;
    bytesIn.set(new byte[] {});
    try {
      continuation.read(buff);
      fail();
    } catch (EOFException expected) {
    }

    // When there are no bytes left in this frame, but it isn't the last, read continuation.
    Arrays.fill(buff, (byte) -1);
    continuation.bytesLeft = 0;
    continuation.endHeaders = false; // Read continuation.
    bytesIn.set(lastContinuationFrame(new byte[] {1, 2, 3}));
    assertEquals(3, continuation.read(buff));
    assertTrue(Arrays.equals(buff, new byte[] {1, 2, 3}));
    assertEquals(0, continuation.bytesLeft);
  }

  static byte[] lastContinuationFrame(byte[] headerBlock) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);
    dataOut.writeShort(headerBlock.length);
    dataOut.write(Http20Draft09.TYPE_CONTINUATION);
    dataOut.write(Http20Draft09.FLAG_END_HEADERS);
    dataOut.writeInt(0);
    dataOut.write(headerBlock);
    return out.toByteArray();
  }
}
