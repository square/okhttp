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
import static com.squareup.okhttp.internal.spdy.Http20Draft09.FLAG_END_STREAM;
import static com.squareup.okhttp.internal.spdy.Http20Draft09.TYPE_DATA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ContinuationInputStreamTest {
  private final MutableByteArrayInputStream bytesIn = new MutableByteArrayInputStream();
  private final ContinuationInputStream continuation =
      new ContinuationInputStream(new DataInputStream(bytesIn));

  @Test public void readCantOverrunHeaderPayload() throws IOException {
    bytesIn.set(onlyHeadersPayloadFollowedByData());

    continuation.length = continuation.left = 3;
    continuation.flags = Http20Draft09.FLAG_END_HEADERS;
    continuation.streamId = 12345;

    assertEquals(1, continuation.read());
    assertEquals(2, continuation.read());
    assertEquals(3, continuation.read());

    try {
      continuation.read();
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void readCantOverrunHeaderContinuationPayload() throws IOException {
    bytesIn.set(headersPayloadWithContinuationFollowedByData());

    continuation.length = continuation.left = 2;
    continuation.flags = Http20Draft09.FLAG_NONE;
    continuation.streamId = 12345;

    assertEquals(1, continuation.read());
    assertEquals(2, continuation.read());
    assertEquals(3, continuation.read());
    assertEquals(0, continuation.available());

    try {
      continuation.read();
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void availableWithContinuation() throws IOException {
    bytesIn.set(headersPayloadWithContinuationFollowedByData());

    continuation.length = continuation.left = 2;
    continuation.flags = Http20Draft09.FLAG_NONE;
    continuation.streamId = 12345;

    assertEquals(1, continuation.read());
    assertEquals(2, continuation.read()); // exhaust frame one

    assertEquals(0, continuation.left);
    assertEquals(1, continuation.available()); // lazy reads next

    assertEquals(1, continuation.length);
    assertEquals(1, continuation.left);
    assertEquals(3, continuation.read());

    assertEquals(0, continuation.available());
    assertEquals(0, continuation.left);

    try {
      continuation.read();
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void readArrayCantOverrunHeaderPayload() throws IOException {
    bytesIn.set(onlyHeadersPayloadFollowedByData());

    continuation.length = continuation.left = 3;
    continuation.flags = Http20Draft09.FLAG_END_HEADERS;
    continuation.streamId = 12345;

    byte[] buff = new byte[3];
    assertEquals(3, continuation.read(buff));
    assertTrue(Arrays.equals(buff, new byte[] {1, 2, 3}));

    try {
      continuation.read(buff);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void readArrayCantOverrunHeaderContinuationPayload() throws IOException {
    bytesIn.set(headersPayloadWithContinuationFollowedByData());

    continuation.length = continuation.left = 2;
    continuation.flags = Http20Draft09.FLAG_NONE;
    continuation.streamId = 12345;

    byte[] buff = new byte[3];
    assertEquals(3, continuation.read(buff));
    assertTrue(Arrays.equals(buff, new byte[] {1, 2, 3}));

    try {
      continuation.read(buff);
      fail();
    } catch (EOFException expected) {
    }
  }

  static byte[] onlyHeadersPayloadFollowedByData() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);
    dataOut.write(new byte[] {1, 2, 3});
    dataOut.writeShort(0);
    dataOut.write(TYPE_DATA);
    dataOut.write(FLAG_END_STREAM);
    dataOut.writeInt(0xFFFFFFFF);
    return out.toByteArray();
  }

  static byte[] headersPayloadWithContinuationFollowedByData() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);
    dataOut.write(new byte[] {1, 2});
    dataOut.writeShort(1);
    dataOut.write(Http20Draft09.TYPE_CONTINUATION);
    dataOut.write(Http20Draft09.FLAG_END_HEADERS);
    dataOut.writeInt(12345);
    dataOut.write(3);
    dataOut.writeShort(0);
    dataOut.write(TYPE_DATA);
    dataOut.write(FLAG_END_STREAM);
    dataOut.writeInt(0xFFFFFFFF);
    return out.toByteArray();
  }
}
