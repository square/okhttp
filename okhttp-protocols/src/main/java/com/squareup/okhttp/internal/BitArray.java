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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;

/** A simple bitset which supports left shifting. */
public interface BitArray {

  void clear();

  void set(int index);

  void toggle(int index);

  boolean get(int index);

  void shiftLeft(int count);

  /** Bit set that only supports settings bits 0 - 63. */
  public final class FixedCapacity implements BitArray {
    long data = 0x0000000000000000L;

    @Override public void clear() {
      data = 0x0000000000000000L;
    }

    @Override public void set(int index) {
      data |= (1L << checkInput(index));
    }

    @Override public void toggle(int index) {
      data ^= (1L << checkInput(index));
    }

    @Override public boolean get(int index) {
      return ((data >> checkInput(index)) & 1L) == 1;
    }

    @Override public void shiftLeft(int count) {
      data = data << checkInput(count);
    }

    @Override public String toString() {
      return Long.toBinaryString(data);
    }

    public BitArray toVariableCapacity() {
      return new VariableCapacity(this);
    }

    private static int checkInput(int index) {
      if (index < 0 || index > 63) {
        throw new IllegalArgumentException(format("input must be between 0 and 63: %s", index));
      }
      return index;
    }
  }

  /** Bit set that grows as needed. */
  public final class VariableCapacity implements BitArray {

    long[] data;

    // Start offset which allows for cheap shifting. Data is always kept on 64-bit bounds but we
    // offset the outward facing index to support shifts without having to move the underlying bits.
    private int start; // Valid values are [0..63]

    public VariableCapacity() {
      data = new long[1];
    }

    private VariableCapacity(FixedCapacity small) {
      data = new long[] {small.data, 0};
    }

    private void growToSize(int size) {
      long[] newData = new long[size];
      if (data != null) {
        System.arraycopy(data, 0, newData, 0, data.length);
      }
      data = newData;
    }

    private int offsetOf(int index) {
      index += start;
      int offset = index / 64;
      if (offset > data.length - 1) {
        growToSize(offset + 1);
      }
      return offset;
    }

    private int shiftOf(int index) {
      return (index + start) % 64;
    }

    @Override public void clear() {
      Arrays.fill(data, 0);
    }

    @Override public void set(int index) {
      checkInput(index);
      int offset = offsetOf(index);
      data[offset] |= 1L << shiftOf(index);
    }

    @Override public void toggle(int index) {
      checkInput(index);
      int offset = offsetOf(index);
      data[offset] ^= 1L << shiftOf(index);
    }

    @Override public boolean get(int index) {
      checkInput(index);
      int offset = offsetOf(index);
      return (data[offset] & (1L << shiftOf(index))) != 0;
    }

    @Override public void shiftLeft(int count) {
      start -= checkInput(count);
      if (start < 0) {
        int arrayShift = (start / -64) + 1;
        long[] newData = new long[data.length + arrayShift];
        System.arraycopy(data, 0, newData, arrayShift, data.length);
        data = newData;
        start = 64 + (start % 64);
      }
    }

    @Override public String toString() {
      StringBuilder builder = new StringBuilder("{");
      List<Integer> ints = toIntegerList();
      for (int i = 0, count = ints.size(); i < count; i++) {
        if (i > 0) {
          builder.append(',');
        }
        builder.append(ints.get(i));
      }
      return builder.append('}').toString();
    }

    List<Integer> toIntegerList() {
      List<Integer> ints = new ArrayList<Integer>();
      for (int i = 0, count = data.length * 64 - start; i < count; i++) {
        if (get(i)) {
          ints.add(i);
        }
      }
      return ints;
    }

    private static int checkInput(int index) {
      if (index < 0) {
        throw new IllegalArgumentException(format("input must be a positive number: %s", index));
      }
      return index;
    }
  }
}
