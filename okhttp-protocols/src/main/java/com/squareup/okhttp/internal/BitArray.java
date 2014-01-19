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

/** A simple bitset which supports left shifting. */
public final class BitArray {

  long[] data;

  // Start offset which allows for cheap shifting. Data is always kept on 64-bit bounds but we
  // offset the outward facing index to support shifts without having to move the underlying bits.
  private int start; // Valid values are [0..63]

  public BitArray() {
    data = new long[1];
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

  public void clear() {
    Arrays.fill(data, 0);
  }

  public void set(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("index < 0: " + index);
    }
    int offset = offsetOf(index);
    data[offset] |= 1L << shiftOf(index);
  }

  public void toggle(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("index < 0: " + index);
    }
    int offset = offsetOf(index);
    data[offset] ^= 1L << shiftOf(index);
  }

  public boolean get(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("index < 0: " + index);
    }
    int offset = offsetOf(index);
    return (data[offset] & (1L << shiftOf(index))) != 0;
  }

  public void shiftLeft(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("count < 0: " + count);
    }
    start -= count;
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
}
