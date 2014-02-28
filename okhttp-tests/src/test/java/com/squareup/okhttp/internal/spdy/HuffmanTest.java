/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.spdy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Original version of this class was lifted from {@code com.twitter.hpack.HuffmanTest}.
 */
public class HuffmanTest {

  @Test public void roundTripForRequestAndResponse() throws IOException {
    String s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (int i = 0; i < s.length(); i++) {
      assertRoundTrip(s.substring(0, i).getBytes());
    }

    Random random = new Random(123456789L);
    byte[] buf = new byte[4096];
    random.nextBytes(buf);
    assertRoundTrip(buf);
  }

  private void assertRoundTrip(byte[] buf) throws IOException {
    assertRoundTrip(Huffman.Codec.REQUEST, buf);
    assertRoundTrip(Huffman.Codec.RESPONSE, buf);
  }

  private static void assertRoundTrip(Huffman.Codec codec, byte[] buf) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);

    codec.encode(buf, dos);
    assertEquals(baos.size(), codec.encodedLength(buf));

    byte[] decodedBytes = codec.decode(baos.toByteArray());
    assertTrue(Arrays.equals(buf, decodedBytes));
  }
}
