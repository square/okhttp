/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.squareup.okhttp.internal;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ByteSequence}.
 */
public final class ByteSequenceTest {
  @Test public void contentEquals() {
    final byte[] data1 = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
    final byte[] data2 = { 3, 4, 5, 6, 7 };
    final int d2offset = 3;
    ByteSequence bs = new ByteSequence(data1, 0, data1.length);
    assertFalse(bs.contentEquals(data2));
    bs.reset(data1, d2offset, data2.length);
    assertTrue(bs.contentEquals(data2));
    bs.reset(data2, 0, data2.length);
    assertFalse(bs.contentEquals(data1));
    assertTrue(bs.contentEquals(data1, d2offset, data2.length));
    bs.reset(data1, d2offset + 1, data2.length - 1);
    assertFalse(bs.contentEquals(data2));
    assertTrue(bs.contentEquals(data2, 1, data2.length - 1));
  }

  @Test public void toInt() {
    ByteSequence bs = new ByteSequence();
    for (int i = -100; i <= 100; ++i) {
      byte[] data = String.valueOf(i).getBytes();
      bs.reset(data, 0, data.length);
      assertEquals(i, bs.toInt());
    }
    int x = 1;
    for (int j = 1; j <= 10; ++j) {
      for (int i : new int[] { x, x - 1, -x, -x + 1 }) {
        byte[] data = String.valueOf(i).getBytes();
        bs.reset(data, 0, data.length);
        assertEquals(i, bs.toInt());
      }
      x *= 10;
    }
    for (int i : new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE / 10,
        Integer.MAX_VALUE / 10, Integer.MIN_VALUE / 10 - 1, Integer.MAX_VALUE / 10 + 1}) {
      byte[] data = String.valueOf(i).getBytes();
      bs.reset(data, 0, data.length);
      assertEquals(i, bs.toInt());
    }
    for (long i : new long[] { Integer.MIN_VALUE - 1L, Integer.MAX_VALUE + 1L }) {
      byte[] data = String.valueOf(i).getBytes();
      bs.reset(data, 0, data.length);
      try {
        bs.toInt();
        fail("Expected NumberFormatException");
      } catch (NumberFormatException expected) {
      }
    }
    for (String s : new String[] { "1/", "9:", "1 2" }) {
      byte[] data = s.getBytes();
      bs.reset(data, 0, data.length);
      try {
        bs.toInt();
        fail("Expected NumberFormatException");
      } catch (NumberFormatException expected) {
      }
    }
  }

  @Test public void rangeToInt() {
    // test a few arbitrary numbers
    int baseOffset = 5;
    byte[] data = new byte[baseOffset + 30];
    ByteSequence bs = new ByteSequence(data, baseOffset, 30);
    int i = 123;
    for (int j = 0; j < 30; ++j) {
      byte[] baseData = String.valueOf(i).getBytes();
      int offset = (i >> 1) & 7;
      Arrays.fill(data, (byte) 'x');
      System.arraycopy(baseData, 0, data, baseOffset + offset, baseData.length);
      assertEquals(i, bs.toInt(offset, offset + baseData.length));
      i = i * 7 - 24;  // we're fine with overflow
    }
  }

  @Test public void toLong() {
    ByteSequence bs = new ByteSequence();
    for (long i = -100; i <= 100; ++i) {
      byte[] data = String.valueOf(i).getBytes();
      bs.reset(data, 0, data.length);
      assertEquals(i, bs.toLong());
    }
    long x = 1;
    for (int j = 1; j <= 19; ++j) {
      for (long i : new long[] { x, x - 1, -x, -x + 1 }) {
        byte[] data = String.valueOf(i).getBytes();
        bs.reset(data, 0, data.length);
        assertEquals(i, bs.toLong());
      }
      x *= 10;
    }
    for (long i : new long[] { Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE / 10,
        Long.MAX_VALUE / 10, Long.MIN_VALUE / 10 - 1, Long.MAX_VALUE / 10 + 1 }) {
      byte[] data = String.valueOf(i).getBytes();
      bs.reset(data, 0, data.length);
      assertEquals(i, bs.toLong());
    }
    for (long i : new long[] { Long.MIN_VALUE, Long.MAX_VALUE }) {
      byte[] data = String.valueOf(i).getBytes();
      data[data.length - 1] += 1;  // get out of valid long range (no overflow on last digit)
      bs.reset(data, 0, data.length);
      try {
        bs.toLong();
        fail("Expected NumberFormatException");
      } catch (NumberFormatException expected) {
      }
    }
    for (String s : new String[] { "1/", "9:", "1 2", "9876543210." }) {
      byte[] data = s.getBytes();
      bs.reset(data, 0, data.length);
      try {
        bs.toLong();
        fail("Expected NumberFormatException");
      } catch (NumberFormatException expected) {
      }
    }
  }

  @Test public void rangeToLong() {
    // test a few arbitrary numbers
    int baseOffset = 5;
    byte[] data = new byte[baseOffset + 30];
    ByteSequence bs = new ByteSequence(data, baseOffset, 30);
    long i = 321L;
    for (int j = 0; j < 30; ++j) {
      byte[] baseData = String.valueOf(i).getBytes();
      int offset = (int) ((i >> 1) & 7);
      Arrays.fill(data, (byte) 'x');
      System.arraycopy(baseData, 0, data, baseOffset + offset, baseData.length);
      assertEquals(i, bs.toLong(offset, offset + baseData.length));
      i = i * 9L - 42L;  // we're fine with overflow
    }
  }

  @Test public void writeSmallInt() {
    byte[] data = new byte[11];
    ByteSequence bs = new ByteSequence(data, 0, 0);
    for (int i = -9; i < 10; ++i) {
      writeIntAssertEqual(bs, i);
    }
    for (int j = 1; j < 2000; ++j) {
      // Check that the division by 10 is correct by testing the breaking points.
      // For j < 1000, we're testing writing head, for j >= 1000 we're testing writing tail.
      for (int i : new int[] { 10 * j, 10 * j + 9, -(10 * j), -(10 * j + 9) }) {
        writeIntAssertEqual(bs, i);
      }
    }
  }

  @Test public void writeLargeInt() {
    byte[] data = new byte[11];
    ByteSequence bs = new ByteSequence(data, 0, 0);
    for (int j = 1; j <= Integer.MAX_VALUE / 10000; ++j) {
      // Check that the division by 10000 is correct by testing the breaking points.
      for (int l = 0; l <= 1; ++l) {
        int i = 10000 * j + 9999 * l;
        Arrays.fill(data, (byte) 'x');
        bs.truncate(0).appendInt(i);
        // Rely on ByteSequence.toInt() for speed instead of using Integer.parseInt()
        // which allocates 2 chunks of memory; ByteSequence.toInt() is tested separately.
        assertEquals(i, bs.toInt());
      }
    }
    for (int i : new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE }) {
      writeIntAssertEqual(bs, i);
    }
  }

  @Test public void writeSmallLong() {
    // Test a few values that would fit into an int.
    byte[] data = new byte[20];
    ByteSequence bs = new ByteSequence(data, 0, 0);
    for (long i : new long[] { Integer.MIN_VALUE, Integer.MAX_VALUE }) {
      writeLongAssertEqual(bs, i);
    }
    long i = 7L;
    for (long j = 0; j < 100; ++j) {
      writeLongAssertEqual(bs, i);
      i = 18 - ((int) i) * 3;  // we're fine with overflow
    }
  }

  @Test public void writeMediumLong() {
    // We shall not fully test the division by 1000000000 (that would take too long)
    // or the writing of the leading digits by code shared with appendInt(.).
    // We'll just test writing the bottom 9 digits for  2^32 <= abs(n) < 2^32 * 10^9 .

    byte[] data = new byte[20];
    ByteSequence bs = new ByteSequence(data, 0, 0);
    final long bln = 1000000000L;  // billion

    for (long j = 0; j < 10; ++j) {
      long k = 10 * bln + j;
      for (long i : new long[] { k, -k }) {
        writeLongAssertEqual(bs, i);
      }
    }
    for (long j = 1; j <= 1000; ++j) {
      // Check that the division by 10 is correct by testing the breaking points.
      long k = 10 * bln + j * 10;
      for (long i : new long[] { k, k + 9, -k, -(k + 9) }) {
        writeLongAssertEqual(bs, i);
      }
    }
    for (long j = 1; j < 100000; ++j) {
      // Check that the division by 10000 is correct by testing the breaking points.
      long k = 10 * bln + j * 10000;
      for (long l = 0; l <= 1; ++l) {
        long i = k + l * 9999;
        Arrays.fill(data, (byte) 'x');
        bs.truncate(0).appendLong(i);
        // Rely on ByteSequence.toLong() for speed instead of using Long.parseLong()
        // which allocates 2 chunks of memory; ByteSequence.toLong() is tested separately.
        assertEquals(i, bs.toLong());
      }
    }
    for (long j : new long[] { Integer.MIN_VALUE, Integer.MAX_VALUE }) {
      long i = j * bln;
      writeLongAssertEqual(bs, i);
    }
    for (long i = Integer.MIN_VALUE * bln + 1234; i < Integer.MAX_VALUE * bln;
        i += 1272173234789641L) {  // a few other arbitrary numbers
      writeLongAssertEqual(bs, i);
    }
  }

  @Test public void writeLargeLong() {
    // We shall not fully test the division by 1000000000 or the subsequent division by 10
    // (that would take too long) but limit ourselves to a decent test of the division by 10
    // for  abs(n) > 2^32 * 10^9 . Storing 9 digits is thoroughly tested in testWriteMediumLong().

    byte[] data = new byte[20];
    ByteSequence bs = new ByteSequence(data, 0, 0);
    final long bln = 1000000000L;  // billion

    for (long j = 0; j < 1000; ++j) {
      long k1 = (0x100000000L + j) * bln;  // lowest inputs to  0x1999999 * x  in division by 10
      long k2 = (0x1ffffffffL - j) * bln;  // highest inputs to  0x1999999 * x  in division by 10
      long k3 = Long.MAX_VALUE - j * bln;  // highest  abs(n)  after division by 1000000000
      for (long i : new long[] { k1, -k1, k2, -k2, k3, -k3 - 1L }) {
        writeLongAssertEqual(bs, i);
      }
    }
    for (long j = bln << 32 + 4321; j > 0; j += 6272173234789641L) {
      for (long i : new long[] { j, -j }) {  // a few other arbitrary numbers
        writeLongAssertEqual(bs, i);
      }
    }
  }

  @Test public void writeIntCorrectRealloc() {
    int i = 1;
    for (int j = 1; j <= 10; ++j) {
      // i = 10^(j-1)
      byte[] data = new byte[2 + j];

      // Write i with j digits, test that we don't reallocate.
      int offset = 2;
      ByteSequence bs = new ByteSequence(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendInt(i);
      assertEquals(i, Integer.parseInt(bs.toString()));
      assertTrue(bs.data() == data && bs.offset() == offset && bs.length() == j);

      // Write -i with j+1 digits, test that we don't reallocate.
      offset = 1;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendInt(-i);
      assertEquals(-i, Integer.parseInt(bs.toString()));
      assertTrue(bs.data() == data && bs.offset() == offset && bs.length() == j + 1);

      // Write i with j digits with insufficient space, test that we do reallocate.
      offset = 3;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendInt(i);
      assertEquals(i, Integer.parseInt(bs.toString()));
      assertTrue(bs.data() != data);

      // Write -i with j+1 digits with insufficient space, test that we do reallocate.
      offset = 2;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendInt(-i);
      assertEquals(-i, Integer.parseInt(bs.toString()));
      assertTrue(bs.data() != data);

      i *= 10;
    }
  }

  @Test public void writeLongCorrectRealloc() {
    long i = 1;
    for (int k = 1; k <= 20; ++k) {
      int j = k;
      // i = 10^(j-1) up to the highest long power of 10, 10^18, which is less than 2^32 * 10^9.
      // So, for k == 20, test with Long.MAX_VALUE to check the path where i / 10^9 >= 2^32.
      if (j == 20) {
        i = Long.MAX_VALUE;
        j = 19;
      }
      byte[] data = new byte[2 + j];

      // Write i with j digits, test that we don't reallocate.
      int offset = 2;
      ByteSequence bs = new ByteSequence(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendLong(i);
      assertEquals(i, Long.parseLong(bs.toString()));
      assertTrue(bs.data() == data && bs.offset() == offset && bs.length() == j);

      // Write -i with j+1 digits, test that we don't reallocate.
      offset = 1;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendLong(-i);
      assertEquals(-i, Long.parseLong(bs.toString()));
      assertTrue(bs.data() == data && bs.offset() == offset && bs.length() == j + 1);

      // Write i with j digits with insufficient space, test that we do reallocate.
      offset = 3;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendLong(i);
      assertEquals(i, Long.parseLong(bs.toString()));
      assertTrue(bs.data() != data);

      // Write -i with j+1 digits with insufficient space, test that we do reallocate.
      offset = 2;
      bs.reset(data, offset, 0);
      Arrays.fill(data, (byte) 'x');
      bs.appendLong(-i);
      assertEquals(-i, Long.parseLong(bs.toString()));
      assertTrue(bs.data() != data);

      i *= 10L;
    }
  }

  private void writeIntAssertEqual(ByteSequence bs, int i) {
    Arrays.fill(bs.data(), (byte) 'x');
    bs.truncate(0).appendInt(i);
    assertEquals(i, Integer.parseInt(bs.toString()));
  }

  private void writeLongAssertEqual(ByteSequence bs, long i) {
    Arrays.fill(bs.data(), (byte) 'x');
    bs.truncate(0).appendLong(i);
    assertEquals(i, Long.parseLong(bs.toString()));
  }
}
