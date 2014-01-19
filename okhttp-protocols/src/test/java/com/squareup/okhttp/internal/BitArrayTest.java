/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal;

import java.math.BigInteger;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BitArrayTest {

  @Test public void setExpandsData() {
    BitArray b = new BitArray();
    b.set(64);
    assertEquals(asList(64), b.toIntegerList());
  }

  @Test public void toggleBit() {
    BitArray b = new BitArray();
    b.set(100);
    b.toggle(100);
    assertTrue(b.toIntegerList().isEmpty());
    b.toggle(1);
    assertEquals(asList(1), b.toIntegerList());
  }

  @Test public void shiftLeftExpandsData() {
    BitArray b = new BitArray();
    b.set(0);
    b.shiftLeft(64);
    assertEquals(asList(64), b.toIntegerList());
  }

  @Test public void shiftLeftFromZero() {
    BitArray b = new BitArray();
    b.set(0);
    b.shiftLeft(1);
    assertEquals(asList(1), b.toIntegerList());
  }

  @Test public void shiftLeftAcrossOffset() {
    BitArray b = new BitArray();
    b.set(63);
    assertEquals(1, b.data.length);
    b.shiftLeft(1);
    assertEquals(asList(64), b.toIntegerList());
    assertEquals(2, b.data.length);
  }

  @Test public void multipleShiftsLeftAcrossOffset() {
    BitArray b = new BitArray();
    b.set(1000);
    b.shiftLeft(67);
    assertEquals(asList(1067), b.toIntegerList());
    b.shiftLeft(69);
    assertEquals(asList(1136), b.toIntegerList());
  }

  @Test public void clearBits() {
    BitArray b = new BitArray();
    b.set(10);
    b.set(100);
    b.set(1000);
    b.clear();
    assertTrue(b.toIntegerList().isEmpty());
  }

  @Test public void bigIntegerSanityCheck() {
    BitArray a = new BitArray();
    BigInteger b = BigInteger.ZERO;

    a.set(64);
    b = b.setBit(64);
    assertEquals(bigIntegerToString(b), a.toString());

    a.set(1000000);
    b = b.setBit(1000000);
    assertEquals(bigIntegerToString(b), a.toString());

    a.shiftLeft(100);
    b = b.shiftLeft(100);
    assertEquals(bigIntegerToString(b), a.toString());

    a.set(0xF00D);
    b = b.setBit(0xF00D);
    a.set(0xBEEF);
    b = b.setBit(0xBEEF);
    a.set(0xDEAD);
    b = b.setBit(0xDEAD);
    assertEquals(bigIntegerToString(b), a.toString());

    a.shiftLeft(0xB0B);
    b = b.shiftLeft(0xB0B);
    assertEquals(bigIntegerToString(b), a.toString());

    a.toggle(64280);
    b = b.clearBit(64280);
    assertEquals(bigIntegerToString(b), a.toString());
  }

  private static String bigIntegerToString(BigInteger b) {
    StringBuilder builder = new StringBuilder("{");
    for (int i = 0, count = b.bitLength(); i < count; i++) {
      if (b.testBit(i)) {
        builder.append(i).append(',');
      }
    }
    builder.setCharAt(builder.length() - 1, '}');
    return builder.toString();
  }
}
