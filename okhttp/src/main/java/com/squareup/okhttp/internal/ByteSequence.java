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

import java.io.UnsupportedEncodingException;

/**
 * ByteSequence is a limited subset of {@link String} and {@link StringBuilder} functionality
 * over a byte array. It is used for efficient parsing and writing of byte-based data.
 *
 * <p> The underlying byte array must be allocated externally and given to the constructor or
 * {@link #reset(byte[], int, int)}. It is then passed around by substring functions.
 * Unlike {@link String}, {@code ByteSequence} exposes the underlying array and avoids copying
 * the data unless explicitly requested by the copy constructor {@link #ByteSequence(ByteSequence)}
 * or necessary for one of the append functions.
 */
public final class ByteSequence {
  private byte[] value;
  private int offset;
  private int count;
  private int hashCode;

  /**
   * Create an empty ByteSequence.
   */
  public ByteSequence() {
    reset(Util.EMPTY_BYTE_ARRAY, 0, 0);
  }

  /**
   * Constructs a ByteSequence over a specified byte array chunk.
   *
   * @param value the underlying byte array.
   * @param offset the starting offset of the data chunk.
   * @param count the length of the data chunk.
   */
  public ByteSequence(byte[] value, int offset, int count) {
    reset(value, offset, count);
  }

  /**
   * Constructs a copy of a ByteSequence with a copy of its internal byte array chunk.
   *
   * @param src the ByteSequence to copy.
   */
  public ByteSequence(ByteSequence src) {
    this.offset = 0;
    this.count = src.count;
    this.hashCode = src.hashCode;
    this.value = new byte[src.count];
    System.arraycopy(src.value, src.offset, this.value, 0, this.count);
  }

  /**
   * Reset to a specified byte array chunk.
   *
   * @param value the underlying byte array.
   * @param offset the starting offset of the data chunk.
   * @param count the length of the data chunk.
   */
  public void reset(byte[] value, int offset, int count) {
    if ((offset | count | (offset + count) | (value.length - (offset + count))) < 0) {
      throw rangeCheckFailed(offset, offset + count, value.length);
    }
    this.value = value;
    this.offset = offset;
    this.count = count;
    this.hashCode = 0;
  }

  /**
   * Get the raw underlying byte array.
   *
   * @return the underlying byte array.
   */
  public byte[] data() {
    return value;
  }

  /**
   * Get starting offset of the content in the underlying byte array.
   *
   * @return the starting offset in the underlying byte array.
   */
  public int offset() {
    return offset;
  }

  /**
   * Get the length of the content.
   *
   * @return the length of the content.
   */
  public int length() {
    return count;
  }

  /**
   * Check if the ByteSequence is empty.
   *
   * @return true if empty, false otherwise.
   */
  public boolean isEmpty() {
    return count == 0;
  }

  /**
   * Retrieve byte at a given index.
   *
   * @param index the position of the requested byte.
   */
  public byte byteAt(int index) {
    if (index < 0 || index >= count) {
      throw indexCheckFailed(index, count);
    }
    return value[offset + index];
  }

  /**
   * Assign substring of another ByteSequence from a given position to the end.
   *
   * @param src the source ByteSequence.
   * @param start the starting position of the substring in {@code src}.
   */
  public void assignSubstring(ByteSequence src, int start) {
    assignSubstring(src, start, src.count);
  }

  /**
   * Assign substring of another ByteSequence in range [{@code start}, {@code end}).
   *
   * @param src the source ByteSequence.
   * @param start the starting position of the substring in {@code src}.
   * @param end the end position of the substring in {@code src}.
   */
  public void assignSubstring(ByteSequence src, int start, int end) {
    if (start < 0 || end < start || end > src.count) {
      throw rangeCheckFailed(start, end, src.count);
    }
    reset(src.value, src.offset + start, end - start);
  }

  /**
   * Get a substring from the specified start position to the end.
   * This always allocates a new ByteSequence object but shared the underlying byte array.
   *
   * @param start the start position of the substring.
   * @return the requested substring.
   */
  public ByteSequence substring(int start) {
    return substring(start, count);
  }

  /**
   * Get a substring from the range [{@code start}, {@code end}).
   * This always allocates a new ByteSequence object but shared the underlying byte array.
   *
   * @param start the start position of the substring.
   * @param end the end position of the substring.
   * @return the requested substring.
   */
  public ByteSequence substring(int start, int end) {
    if (start < 0 || end < start || end > count) {
      throw rangeCheckFailed(start, end, count);
    }
    return new ByteSequence(value, offset + start, end - start);
  }

  /**
   * Find a given byte.
   *
   * @param b the byte to look for. (As int to avoid casting character constants to byte.)
   * @return the index of the first {@code b} found, or -1 if not found.
   */
  public int indexOf(int b) {
    return indexOf(b, 0);
  }

  /**
   * Find a given byte from a specified start position.
   *
   * @param b the byte to look for. (As int to avoid casting character constants to byte.)
   * @param pos the starting position of the search.
   * @return the index of the first {@code b} found, or -1 if not found.
   */
  public int indexOf(int b, int pos) {
    if (pos < 0 || pos > count) {
      throw indexCheckFailed(pos, count);
    }
    final int end = offset + count;
    final byte[] bytes = value;
    for (int i = offset + pos; i < end; ++i) {
      if (bytes[i] == b) {
        return i - offset;
      }
    }
    return -1;
  }

  /**
   * Check whether the ByteSequence starts with a specified prefix.
   *
   * @param prefix the prefix to check.
   * @return true if the prefix is present, false otherwise.
   */
  public boolean startsWith(byte[] prefix) {
    final int len = prefix.length;
    if (len > count) {
      return false;
    }
    for (int i = 0; i < len; ++i) {
      if (value[offset + i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Append one byte.
   *
   * @param oneByte the byte to append. (As int to avoid casting character constants to byte.)
   * @return this ByteSequence for chaining.
   */
  public ByteSequence append(int oneByte) {
    if (offset + count == value.length) {
      enlargeBuffer(count + 1);
    }
    value[offset + count] = (byte) oneByte;
    ++count;
    hashCode = 0;
    return this;
  }

  /**
   * Append another ByteSequence.
   *
   * @param bs the ByteSequence to append.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence append(ByteSequence bs) {
    return append(bs.value, bs.offset, bs.count);
  }

  /**
   * Append byte array.
   *
   * @param data the byte array to append.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence append(byte[] data) {
    return append(data, 0, data.length);
  }

  /**
   * Append a byte array chunk.
   *
   * @param data the byte array of the chunk.
   * @param pos the starting position of the chunk.
   * @param length the length of the chunk.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence append(byte[] data, int pos, int length) {
    if ((pos | length | (pos + length) | (data.length - (pos + length))) < 0) {
      throw rangeCheckFailed(pos, pos + length, data.length);
    }
    if (value.length - (offset + count) < length) {
      enlargeBuffer(count + length);
    }
    System.arraycopy(data, pos, value, offset + count, length);
    count += length;
    hashCode = 0;
    return this;
  }

  /**
   * Append an ASCII string.
   *
   * @param ascii the String to append.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence append(String ascii) {
    int length = ascii.length();
    if (value.length - (offset + count) < length) {
      enlargeBuffer(count + length);
    }
    for (int i = 0; i < length; ++i) {
      value[offset + i] = (byte) ascii.charAt(i);
    }
    count += length;
    hashCode = 0;
    return this;
  }

  /**
   * Append an int.
   *
   * @param n the value to append.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence appendInt(int n) {
    appendIntInternal(n < 0 ? -n : n, n < 0, 0);
    return this;
  }

  /**
   * Append a long.
   *
   * @param n the value to append.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence appendLong(long n) {
    int i = (int) n;
    if (i == n) {
      return appendInt(i);
    }
    boolean negative = n <= 0;
    if (negative) {
      n = -n;
      if (n < 0) {
        // Long.MIN_VALUE
        appendIntInternal(922337203, true, 10);
        int end = offset + count;
        value[end - 10] = '6';
        store9Digits(end - 9, 854775808);
        return this;
      }
    }

    int low = (int) (n % 1000000000);
    // Exact division to get the leading digits.
    n = ((n - low) >>> 9) * 0x8E47CE423A2E9C6DL;
    int hi32 = (int) (n >>> 32);
    if (hi32 == 0) {
      appendIntInternal((int) n, negative, 9);
      store9Digits(offset + count - 9, low);
      return this;
    }

    // Calculate n % 10 using modified algorithms from "Hacker's Delight",
    // http://www.hackersdelight.org/divcMore.pdf .

    // Since n is more than 32 bits, use one step of "Remainder by summing digits"
    int x = (int) n;
    x = (x & 0x00ffffff) + (((x >>> 24) + hi32) << 4);

    // Now, use a modified step from the "Remainder by Multiplication and Shifting Right".
    // We shall drop the correction terms and multiply by 0x1999999a instead of 0x19999999.
    // This works only for the range [0, 0x0fffffff] but our input is in [0, 0x01001010].
    // It further limits the result  i = x >>> 28  to the 10 values  i = (n % 10) * 16 / 10,
    // i.e. i = 0, 1, 3, 4, 6, 8, 9, 11, 12, 14.
    x = 0x1999999a * x;

    // Instead of using the table  { 0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 8, 9, 0 }
    // to lookup the remainder, we can calculate the value as  (i * 5 + 6) / 8  for  i != 15.
    // Luckily, we not only never hit  i == 15, the limited range of  i  actually allows us
    // to vary the 6 in that formula from 4 to 7. We use that leeway to introduce 2 bogus bits
    // from  x >>> 26  into the intermediate result to allow a little more parallelization.
    int r = (((x + 0x10000000) >>> 26) + (x >>> 28)) >>> 3;

    // Exact division to get the first 9 digits
    int q = ((int) ((n - r) >>> 1)) * 0xcccccccd;

    // Enlarge if needed, write '-'
    int need = negative ? 20 : 19;
    if (value.length - (offset + count) < need) {
      enlargeBuffer(count + need);
    }
    value[offset + count] = '-';  // unconditionally; shall be overwritten if not negative
    count += need;

    // write q, r and low
    int end = offset + count;
    store9Digits(end - 19, q);
    value[end - 10] = (byte) ('0' + r);
    store9Digits(end - 9, low);
    hashCode = 0;
    return this;
  }

  private void store9Digits(int pos, int n) {
    // Precondition: value < 10^9

    // calculate quotient and remainder modulo 10000.
    int x = (int) ((n * 0xD1B71759L) >>> 45);  // works for up to 31 bits, n < 10^9 < 2^30
    int tail = n - x * 10000;

    // x < 10^5 is up to 17 bits,  n / 5 == (n * 0xcccd) >> 8  works up to 16 bits
    int head = ((x >>> 1) * 0xcccd) >>> 18;  // divide by 2 first, then by 5
    int mid = x - 10 * head;

    // Store 4 digits from head followed by mid, followed by 4 digits from tail
    value[pos + 4] = (byte) (mid + '0');
    // head and tail are less that 16 bit, divide by 5, then by 2; merge >>> 18 with >>> 1
    // constant length loop unrolled
    int h = (head * 0xcccd) >>> 19;
    int t = (tail * 0xcccd) >>> 19;
    value[pos + 3] = (byte) (head - 10 * h + '0');
    value[pos + 8] = (byte) (tail - 10 * t + '0');
    head = (h * 0xcccd) >>> 19;
    tail = (t * 0xcccd) >>> 19;
    value[pos + 2] = (byte) (h - 10 * head + '0');
    value[pos + 7] = (byte) (t - 10 * tail + '0');
    h = (head * 0xcccd) >>> 19;
    t = (tail * 0xcccd) >>> 19;
    value[pos + 1] = (byte) (head - 10 * h + '0');
    value[pos + 6] = (byte) (tail - 10 * t + '0');
    value[pos + 0] = (byte) (h + '0');
    value[pos + 5] = (byte) (t + '0');
  }

  private void appendIntInternal(int n, boolean negative, int extraSpace) {
    // Treat value as unsigned

    // x / 10000 == ((x >>> 1) * 0xD1B71759L) >>> 44  works for all 32 bit values
    int head = (int) (((n >>> 1) * 0xD1B71759L) >>> 44);
    int tail = n - head * 10000;
    int mid = 0;

    // Finish the split and calculate the number of digits.
    int digits;
    int top;
    if (head >= 10000) {
      top = (int) ((head * 0xD1B71759L) >>> 45);  // works for up to 31 bits, n < 10^6 < 2^20
      mid = head - top * 10000;
      head = top;
      digits = 10;
    } else {
      if (head == 0) {
        head = tail;
        digits = 4;
      } else {
        digits = 8;
      }
      top = (head * 0x147b) >>> 19;  // x / 100; works for up to 14 bits, head < 10^4 < 2^14
      if (top == 0) {
        digits -= 2;
        top = head - top * 100;
      }
    }
    digits = (top < 10) ? digits - 1 : digits;

    // Enlarge if needed, write '-'
    int need = (digits + extraSpace) + (negative ? 1 : 0);
    if (value.length - (offset + count) < need) {
        enlargeBuffer(count + need);
    }
    value[offset + count] = '-';  // unconditionally; shall be overwritten if not negative
    count += need;

    // Write data using division  n / 10 == (n * 0xcccd) >>> 19  that works up to 16 bits
    int pos = offset + count - extraSpace;
    while (digits > 4) {
      // Write tail; constant length loop unrolled
      int x = (tail * 0xcccd) >>> 19;  // tail < 10^4 < 2^14
      value[--pos] = (byte) (tail - x * 10 + '0');
      tail = (x * 0xcccd) >>> 19;
      value[--pos] = (byte) (x - tail * 10 + '0');
      x = (tail * 0xcccd) >>> 19;
      value[--pos] = (byte) (tail - x * 10 + '0');
      value[--pos] = (byte) (x + '0');
      tail = mid;  // write mid in the next iteration (if digits was more than 8).
      digits -= 4;
    }
    // Write the most significant digits
    for (; digits > 1; --digits) {
      int x = (head * 0xcccd) >>> 19;  // n < 10^4 < 2^14
      value[--pos] = (byte) (head - x * 10 + '0');
      head = x;
    }
    value[--pos] = (byte) (head + '0');
    hashCode = 0;
  }

  private void enlargeBuffer(int minSize) {
    // Over-allocate as StringBuilder.
    byte[] bytes = new byte[minSize + (minSize >> 1) + 2];
    System.arraycopy(value, offset, bytes, 0, count);
    value = bytes;
    offset = 0;
  }

  /**
   * Truncate the ByteSequence to a specified length. The new length must be in the range
   * [0, {@code length()}), the function cannot extend the ByteSequence.
   *
   * @param length the requested length.
   * @return this ByteSequence for chaining.
   */
  public ByteSequence truncate(int length) {
    if (length < 0 || length > count) {
      throw indexCheckFailed(length, count);
    }
    count = length;
    return this;
  }

  /**
   * Calculate the hash code.
   *
   * @return hash code for this ByteSequence.
   */
  @Override public int hashCode() {
    int hash = hashCode;
    if (hash == 0) {
      if (count == 0) {
        return 0;
      }
      final int end = offset + count;
      final byte[] bytes = value;
      for (int i = offset; i < end; ++i) {
        hash = 31 * hash + bytes[i];
      }
      hashCode = hash;
    }
    return hash;
  }

  /**
   * Check if another {@code object} is an equivalent ByteSequence.
   *
   * @param object the object to compare with.
   * @return true if the object is an equivalent ByteSequence, false otherwise.
   */
  @Override public boolean equals(Object object) {
    if (!(object instanceof ByteSequence)) {
      return false;
    }
    ByteSequence other = (ByteSequence) object;
    if (hashCode != other.hashCode && hashCode != 0 && other.hashCode != 0) {
      return false;
    }
    return contentEqualsInternal(other.value, other.offset, other.count);
  }

  /**
   * Compare contents with a byte array.
   *
   * @param data the byte array to compare with.
   * @return true if the content equals the data, false otherwise.
   */
  public boolean contentEquals(byte[] data) {
    return contentEqualsInternal(data, 0, data.length);
  }

  /**
   * Compare contents with a byte array chunk.
   *
   * @param data the byte array of the chunk to compare with.
   * @param pos the starting position of the chunk.
   * @param length the length of the chunk.
   * @return true if the content equals the data, false otherwise.
   */
  public boolean contentEquals(byte[] data, int pos, int length) {
    if ((pos | length | (pos + length) | (data.length - (pos + length))) < 0) {
      throw rangeCheckFailed(pos, pos + length, data.length);
    }
    return contentEqualsInternal(data, pos, length);
  }

  private boolean contentEqualsInternal(byte[] data, int pos, int length) {
    if (count != length) {
      return false;
    }
    for (int i = 0; i != count; ++i) {
      if (value[offset + i] != data[pos + i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Compare contents with an ASCII string.
   *
   * @param ascii the String to compare with.
   * @return true if the content equals the data, false otherwise.
   */
  public boolean contentEquals(String ascii) {
    if (count != ascii.length()) {
      return false;
    }
    for (int i = 0; i != count; ++i) {
      if (value[offset + i] != ascii.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Convert to String. Uses UTF-8 encoding.
   *
   * @return the String representation of this ByteSequence.
   */
  @Override public String toString() {
    try {
      return (count == 0) ? "" : new String(value, offset, count, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Convert to String using a charset specified by name.
   *
   * @param charsetName the name of the charset to use.
   * @return contents of this ByteSequence converted to String using the specifed charset.
   * @throws UnsupportedEncodingException if the named charset is not supported.
   */
  public String toString(String charsetName) throws UnsupportedEncodingException {
    return (count == 0) ? "" : new String(value, offset, count, charsetName);
  }

  /**
   * Convert to int.
   *
   * @return the parsed int value.
   * @throws NumberFormatException if the content is not a valid int representation.
   */
  public int toInt() {
    return toInt(0, count);
  }

  /**
   * Convert the section of the content in the range [{@code start}, {@code end}) to int.
   *
   * @param start the starting position of the section.
   * @param end the end position of the section.
   * @return the parsed int value.
   * @throws NumberFormatException if the content is not a valid int representation.
   */
  public int toInt(int start, int end) {
    if (start < 0 || end < start || end > count) {
      throw rangeCheckFailed(start, end, count);
    }
    if (end == start) {
      throw invalidInt(start, end);
    }
    int index = offset + start;
    int endIndex = offset + end;
    final boolean negative = value[index] == '-';
    if (negative && ++index == endIndex) {
      throw invalidInt(start, end);
    }

    final int max = Integer.MIN_VALUE / 10;
    int result = 0;
    while (index < endIndex) {
      if (max > result) {
        throw invalidInt(start, end);
      }
      byte b = value[index++];
      if (b < '0' || b > '9') {
        throw invalidInt(start, end);
      }
      result = result * 10 - (b - '0');
      if (result > 0) {
        throw invalidInt(start, end);
      }
    }
    if (!negative) {
      result = -result;
      if (result < 0) {
        throw invalidInt(start, end);
      }
    }
    return result;
  }

  /**
   * Convert to long.
   *
   * @return the parsed long value.
   * @throws NumberFormatException if the content is not a valid int representation.
   */
  public long toLong() {
    return toLong(0, count);
  }

  /**
   * Convert the section of the content in the range [{@code start}, {@code end}) to long.
   *
   * @param start the starting position of the section.
   * @param end the end position of the section.
   * @return the parsed long value.
   * @throws NumberFormatException if the content is not a valid int representation.
   */
  public long toLong(int start, int end) {
    if (start < 0 || end < start || end > count) {
        throw rangeCheckFailed(start, end, count);
    }
    if (end == start) {
      throw invalidLong(start, end);
    }
    int index = offset + start;
    int endIndex = offset + end;
    final boolean negative = value[index] == '-';
    if (negative && ++index == endIndex) {
      throw invalidLong(start, end);
    }

    final long max = Long.MIN_VALUE / 10;
    long result = 0;
    while (index < endIndex) {
      if (max > result) {
        throw invalidLong(start, end);
      }
      byte b = value[index++];
      if (b < '0' || b > '9') {
        throw invalidLong(start, end);
      }
      result = result * 10 - (b - '0');
      if (result > 0) {
        throw invalidLong(start, end);
      }
    }
    if (!negative) {
      result = -result;
      if (result < 0) {
        throw invalidInt(start, end);
      }
    }
    return result;
  }

  private IndexOutOfBoundsException indexCheckFailed(int index, int size) {
    throw new IndexOutOfBoundsException("Index out of range: " + index + "; size: " + size);
  }

  private IndexOutOfBoundsException rangeCheckFailed(int start, int end, int size) {
    throw new IndexOutOfBoundsException("Invalid range: start = " + start + ", end = " + end
        + "; size = " + size);
  }

  private NumberFormatException invalidInt(int start, int end) {
    throw new NumberFormatException("Invalid int: " + substring(start, end).toString());
  }

  private NumberFormatException invalidLong(int start, int end) {
    throw new NumberFormatException("Invalid long: " + substring(start, end).toString());
  }
}
