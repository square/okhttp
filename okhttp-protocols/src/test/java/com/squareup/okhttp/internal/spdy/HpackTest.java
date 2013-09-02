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
package com.squareup.okhttp.internal.spdy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HpackTest {
  private ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
  private final Hpack.Writer hpackWriter = new Hpack.Writer(new DataOutputStream(bytesOut));

  @Test public void readSingleByteInt() throws IOException {
    assertEquals(10, new Hpack.Reader(byteStream(), true).readInt(10, 31));
    assertEquals(10, new Hpack.Reader(byteStream(), true).readInt(0xe0 | 10, 31));
  }

  @Test public void readMultibyteInt() throws IOException {
    assertEquals(1337, new Hpack.Reader(byteStream(154, 10), true).readInt(31, 31));
  }

  @Test public void writeSingleByteInt() throws IOException {
    hpackWriter.writeInt(10, 31, 0);
    assertBytes(10);
    hpackWriter.writeInt(10, 31, 0xe0);
    assertBytes(0xe0 | 10);
  }

  @Test public void writeMultibyteInt() throws IOException {
    hpackWriter.writeInt(1337, 31, 0);
    assertBytes(31, 154, 10);
    hpackWriter.writeInt(1337, 31, 0xe0);
    assertBytes(0xe0 | 31, 154, 10);
  }

  @Test public void max31BitValue() throws IOException {
    hpackWriter.writeInt(0x7fffffff, 31, 0);
    assertBytes(31, 224, 255, 255, 255, 7);
    assertEquals(0x7fffffff,
        new Hpack.Reader(byteStream(224, 255, 255, 255, 7), true).readInt(31, 31));
  }

  @Test public void prefixMask() throws IOException {
    hpackWriter.writeInt(31, 31, 0);
    assertBytes(31, 0);
    assertEquals(31, new Hpack.Reader(byteStream(0), true).readInt(31, 31));
  }

  @Test public void prefixMaskMinusOne() throws IOException {
    hpackWriter.writeInt(30, 31, 0);
    assertBytes(30);
    assertEquals(31, new Hpack.Reader(byteStream(0), true).readInt(31, 31));
  }

  @Test public void zero() throws IOException {
    hpackWriter.writeInt(0, 31, 0);
    assertBytes(0);
    assertEquals(0, new Hpack.Reader(byteStream(), true).readInt(0, 31));
  }

  @Test public void headerName() throws IOException {
    hpackWriter.writeString("foo");
    assertBytes(3, 'f', 'o', 'o');
    assertEquals("foo", new Hpack.Reader(byteStream(3, 'f', 'o', 'o'), true).readString());
  }

  @Test public void emptyHeaderName() throws IOException {
    hpackWriter.writeString("");
    assertBytes(0);
    assertEquals("", new Hpack.Reader(byteStream(0), true).readString());
  }

  @Test public void headersRoundTrip() throws IOException {
    List<String> sentHeaders = Arrays.asList("name", "value");
    hpackWriter.writeHeaders(sentHeaders);
    ByteArrayInputStream bytesIn = new ByteArrayInputStream(bytesOut.toByteArray());
    Hpack.Reader reader = new Hpack.Reader(new DataInputStream(bytesIn), true);
    reader.readHeaders(bytesOut.size());
    reader.emitReferenceSet();
    List<String> receivedHeaders = reader.getAndReset();
    assertEquals(sentHeaders, receivedHeaders);
  }

  private DataInputStream byteStream(int... bytes) {
    byte[] data = intArrayToByteArray(bytes);
    return new DataInputStream(new ByteArrayInputStream(data));
  }

  private void assertBytes(int... bytes) {
    byte[] expected = intArrayToByteArray(bytes);
    byte[] actual = bytesOut.toByteArray();
    assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    bytesOut.reset(); // So the next test starts with a clean slate.
  }

  private byte[] intArrayToByteArray(int[] bytes) {
    byte[] data = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      data[i] = (byte) bytes[i];
    }
    return data;
  }
}
