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
package okio;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static java.util.Arrays.asList;
import static junit.framework.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class OkBufferTest {
  @Test public void readAndWriteUtf8() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("ab");
    assertEquals(2, buffer.size());
    buffer.writeUtf8("cdef");
    assertEquals(6, buffer.size());
    assertEquals("abcd", buffer.readUtf8(4));
    assertEquals(2, buffer.size());
    assertEquals("ef", buffer.readUtf8(2));
    assertEquals(0, buffer.size());
    try {
      buffer.readUtf8(1);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
  }

  @Test public void completeSegmentByteCountOnEmptyBuffer() throws Exception {
    OkBuffer buffer = new OkBuffer();
    assertEquals(0, buffer.completeSegmentByteCount());
  }

  @Test public void completeSegmentByteCountOnBufferWithFullSegments() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a', Segment.SIZE * 4));
    assertEquals(Segment.SIZE * 4, buffer.completeSegmentByteCount());
  }

  @Test public void completeSegmentByteCountOnBufferWithIncompleteTailSegment() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a', Segment.SIZE * 4 - 10));
    assertEquals(Segment.SIZE * 3, buffer.completeSegmentByteCount());
  }

  @Test public void readUtf8SpansSegments() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a', Segment.SIZE * 2));
    buffer.readUtf8(Segment.SIZE - 1);
    assertEquals("aa", buffer.readUtf8(2));
  }

  @Test public void readUtf8EntireBuffer() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a', Segment.SIZE));
    assertEquals(repeat('a', Segment.SIZE), buffer.readUtf8(Segment.SIZE));
  }

  @Test public void toStringOnEmptyBuffer() throws Exception {
    OkBuffer buffer = new OkBuffer();
    assertEquals("OkBuffer[size=0]", buffer.toString());
  }

  @Test public void toStringOnSmallBufferIncludesContents() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.write(ByteString.decodeHex("a1b2c3d4e5f61a2b3c4d5e6f10203040"));
    assertEquals("OkBuffer[size=16 data=a1b2c3d4e5f61a2b3c4d5e6f10203040]", buffer.toString());
  }

  @Test public void toStringOnLargeBufferIncludesMd5() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.write(ByteString.encodeUtf8("12345678901234567"));
    assertEquals("OkBuffer[size=17 md5=2c9728a2138b2f25e9f89f99bdccf8db]", buffer.toString());
  }

  @Test public void toStringOnMultipleSegmentBuffer() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a', 6144));
    assertEquals("OkBuffer[size=6144 md5=d890021f28522533c1cc1b9b1f83ce73]", buffer.toString());
  }

  @Test public void multipleSegmentBuffers() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a',  1000));
    buffer.writeUtf8(repeat('b', 2500));
    buffer.writeUtf8(repeat('c', 5000));
    buffer.writeUtf8(repeat('d', 10000));
    buffer.writeUtf8(repeat('e', 25000));
    buffer.writeUtf8(repeat('f', 50000));

    assertEquals(repeat('a', 999), buffer.readUtf8(999)); // a...a
    assertEquals("a" + repeat('b', 2500) + "c", buffer.readUtf8(2502)); // ab...bc
    assertEquals(repeat('c', 4998), buffer.readUtf8(4998)); // c...c
    assertEquals("c" + repeat('d', 10000) + "e", buffer.readUtf8(10002)); // cd...de
    assertEquals(repeat('e', 24998), buffer.readUtf8(24998)); // e...e
    assertEquals("e" + repeat('f', 50000), buffer.readUtf8(50001)); // ef...f
    assertEquals(0, buffer.size());
  }

  @Test public void fillAndDrainPool() throws Exception {
    OkBuffer buffer = new OkBuffer();

    // Take 2 * MAX_SIZE segments. This will drain the pool, even if other tests filled it.
    buffer.write(new byte[(int) SegmentPool.MAX_SIZE]);
    buffer.write(new byte[(int) SegmentPool.MAX_SIZE]);
    assertEquals(0, SegmentPool.INSTANCE.byteCount);

    // Recycle MAX_SIZE segments. They're all in the pool.
    buffer.readByteString(SegmentPool.MAX_SIZE);
    assertEquals(SegmentPool.MAX_SIZE, SegmentPool.INSTANCE.byteCount);

    // Recycle MAX_SIZE more segments. The pool is full so they get garbage collected.
    buffer.readByteString(SegmentPool.MAX_SIZE);
    assertEquals(SegmentPool.MAX_SIZE, SegmentPool.INSTANCE.byteCount);

    // Take MAX_SIZE segments to drain the pool.
    buffer.write(new byte[(int) SegmentPool.MAX_SIZE]);
    assertEquals(0, SegmentPool.INSTANCE.byteCount);

    // Take MAX_SIZE more segments. The pool is drained so these will need to be allocated.
    buffer.write(new byte[(int) SegmentPool.MAX_SIZE]);
    assertEquals(0, SegmentPool.INSTANCE.byteCount);
  }

  @Test public void moveBytesBetweenBuffersShareSegment() throws Exception {
    int size = (Segment.SIZE / 2) - 1;
    List<Integer> segmentSizes = moveBytesBetweenBuffers(repeat('a', size), repeat('b', size));
    assertEquals(asList(size * 2), segmentSizes);
  }

  @Test public void moveBytesBetweenBuffersReassignSegment() throws Exception {
    int size = (Segment.SIZE / 2) + 1;
    List<Integer> segmentSizes = moveBytesBetweenBuffers(repeat('a', size), repeat('b', size));
    assertEquals(asList(size, size), segmentSizes);
  }

  @Test public void moveBytesBetweenBuffersMultipleSegments() throws Exception {
    int size = 3 * Segment.SIZE + 1;
    List<Integer> segmentSizes = moveBytesBetweenBuffers(repeat('a', size), repeat('b', size));
    assertEquals(asList(Segment.SIZE, Segment.SIZE, Segment.SIZE, 1,
        Segment.SIZE, Segment.SIZE, Segment.SIZE, 1), segmentSizes);
  }

  private List<Integer> moveBytesBetweenBuffers(String... contents) {
    StringBuilder expected = new StringBuilder();
    OkBuffer buffer = new OkBuffer();
    for (String s : contents) {
      OkBuffer source = new OkBuffer();
      source.writeUtf8(s);
      buffer.write(source, source.size());
      expected.append(s);
    }
    List<Integer> segmentSizes = buffer.segmentSizes();
    assertEquals(expected.toString(), buffer.readUtf8(expected.length()));
    return segmentSizes;
  }

  /** The big part of source's first segment is being moved. */
  @Test public void writeSplitSourceBufferLeft() throws Exception {
    int writeSize = Segment.SIZE / 2 + 1;

    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', Segment.SIZE - 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, writeSize);

    assertEquals(asList(Segment.SIZE - 10, writeSize), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - writeSize, Segment.SIZE), source.segmentSizes());
  }

  /** The big part of source's first segment is staying put. */
  @Test public void writeSplitSourceBufferRight() throws Exception {
    int writeSize = Segment.SIZE / 2 - 1;

    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', Segment.SIZE - 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, writeSize);

    assertEquals(asList(Segment.SIZE - 10, writeSize), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - writeSize, Segment.SIZE), source.segmentSizes());
  }

  @Test public void writePrefixDoesntSplit() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, 20);

    assertEquals(asList(30), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - 20, Segment.SIZE), source.segmentSizes());
    assertEquals(30, sink.size());
    assertEquals(Segment.SIZE * 2 - 20, source.size());
  }

  @Test public void writePrefixDoesntSplitButRequiresCompact() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', Segment.SIZE - 10)); // limit = size - 10
    sink.readUtf8(Segment.SIZE - 20); // pos = size = 20

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, 20);

    assertEquals(asList(30), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - 20, Segment.SIZE), source.segmentSizes());
    assertEquals(30, sink.size());
    assertEquals(Segment.SIZE * 2 - 20, source.size());
  }

  @Test public void readExhaustedSource() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();

    assertEquals(-1, source.read(sink, 10));
    assertEquals(10, sink.size());
    assertEquals(0, source.size());
  }

  @Test public void readZeroBytesFromSource() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();

    // Either 0 or -1 is reasonable here. For consistency with Android's
    // ByteArrayInputStream we return 0.
    assertEquals(-1, source.read(sink, 0));
    assertEquals(10, sink.size());
    assertEquals(0, source.size());
  }

  @Test public void moveAllRequestedBytesWithRead() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('b', 15));

    assertEquals(10, source.read(sink, 10));
    assertEquals(20, sink.size());
    assertEquals(5, source.size());
    assertEquals(repeat('a', 10) + repeat('b', 10), sink.readUtf8(20));
  }

  @Test public void moveFewerThanRequestedBytesWithRead() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('b', 20));

    assertEquals(20, source.read(sink, 25));
    assertEquals(30, sink.size());
    assertEquals(0, source.size());
    assertEquals(repeat('a', 10) + repeat('b', 20), sink.readUtf8(30));
  }

  @Test public void indexOf() throws Exception {
    OkBuffer buffer = new OkBuffer();

    // The segment is empty.
    assertEquals(-1, buffer.indexOf((byte) 'a'));

    // The segment has one value.
    buffer.writeUtf8("a"); // a
    assertEquals(0, buffer.indexOf((byte) 'a'));
    assertEquals(-1, buffer.indexOf((byte) 'b'));

    // The segment has lots of data.
    buffer.writeUtf8(repeat('b', Segment.SIZE - 2)); // ab...b
    assertEquals(0, buffer.indexOf((byte) 'a'));
    assertEquals(1, buffer.indexOf((byte) 'b'));
    assertEquals(-1, buffer.indexOf((byte) 'c'));

    // The segment doesn't start at 0, it starts at 2.
    buffer.readUtf8(2); // b...b
    assertEquals(-1, buffer.indexOf((byte) 'a'));
    assertEquals(0, buffer.indexOf((byte) 'b'));
    assertEquals(-1, buffer.indexOf((byte) 'c'));

    // The segment is full.
    buffer.writeUtf8("c"); // b...bc
    assertEquals(-1, buffer.indexOf((byte) 'a'));
    assertEquals(0, buffer.indexOf((byte) 'b'));
    assertEquals(Segment.SIZE - 3, buffer.indexOf((byte) 'c'));

    // The segment doesn't start at 2, it starts at 4.
    buffer.readUtf8(2); // b...bc
    assertEquals(-1, buffer.indexOf((byte) 'a'));
    assertEquals(0, buffer.indexOf((byte) 'b'));
    assertEquals(Segment.SIZE - 5, buffer.indexOf((byte) 'c'));

    // Two segments.
    buffer.writeUtf8("d"); // b...bcd, d is in the 2nd segment.
    assertEquals(asList(Segment.SIZE - 4, 1), buffer.segmentSizes());
    assertEquals(Segment.SIZE - 4, buffer.indexOf((byte) 'd'));
    assertEquals(-1, buffer.indexOf((byte) 'e'));
  }

  @Test public void indexOfWithOffset() throws Exception {
    OkBuffer buffer = new OkBuffer();
    int halfSegment = Segment.SIZE / 2;
    buffer.writeUtf8(repeat('a', halfSegment));
    buffer.writeUtf8(repeat('b', halfSegment));
    buffer.writeUtf8(repeat('c', halfSegment));
    buffer.writeUtf8(repeat('d', halfSegment));
    assertEquals(0, buffer.indexOf((byte) 'a', 0));
    assertEquals(halfSegment - 1, buffer.indexOf((byte) 'a', halfSegment - 1));
    assertEquals(halfSegment, buffer.indexOf((byte) 'b', halfSegment - 1));
    assertEquals(halfSegment * 2, buffer.indexOf((byte) 'c', halfSegment - 1));
    assertEquals(halfSegment * 3, buffer.indexOf((byte) 'd', halfSegment - 1));
    assertEquals(halfSegment * 3, buffer.indexOf((byte) 'd', halfSegment * 2));
    assertEquals(halfSegment * 3, buffer.indexOf((byte) 'd', halfSegment * 3));
    assertEquals(halfSegment * 4 - 1, buffer.indexOf((byte) 'd', halfSegment * 4 - 1));
  }

  @Test public void writeBytes() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeByte(0xab);
    data.writeByte(0xcd);
    assertEquals("OkBuffer[size=2 data=abcd]", data.toString());
  }

  @Test public void writeLastByteInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 1));
    data.writeByte(0x20);
    data.writeByte(0x21);
    assertEquals(asList(Segment.SIZE, 1), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 1), data.readUtf8(Segment.SIZE - 1));
    assertEquals("OkBuffer[size=2 data=2021]", data.toString());
  }

  @Test public void writeShort() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeShort(0xabcd);
    data.writeShort(0x4321);
    assertEquals("OkBuffer[size=4 data=abcd4321]", data.toString());
  }

  @Test public void writeInt() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals("OkBuffer[size=8 data=abcdef0187654321]", data.toString());
  }

  @Test public void writeLastIntegerInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 4));
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals(asList(Segment.SIZE, 4), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 4), data.readUtf8(Segment.SIZE - 4));
    assertEquals("OkBuffer[size=8 data=abcdef0187654321]", data.toString());
  }

  @Test public void writeIntegerDoesntQuiteFitInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 3));
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals(asList(Segment.SIZE - 3, 8), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 3), data.readUtf8(Segment.SIZE - 3));
    assertEquals("OkBuffer[size=8 data=abcdef0187654321]", data.toString());
  }

  @Test public void readByte() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new byte[] { (byte) 0xab, (byte) 0xcd });
    assertEquals(0xab, data.readByte() & 0xff);
    assertEquals(0xcd, data.readByte() & 0xff);
    assertEquals(0, data.size());
  }

  @Test public void readShort() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01
    });
    assertEquals((short) 0xabcd, data.readShort());
    assertEquals((short) 0xef01, data.readShort());
    assertEquals(0, data.size());
  }

  @Test public void readShortSplitAcrossMultipleSegments() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 1));
    data.write(new byte[] { (byte) 0xab, (byte) 0xcd });
    data.readUtf8(Segment.SIZE - 1);
    assertEquals((short) 0xabcd, data.readShort());
    assertEquals(0, data.size());
  }

  @Test public void readInt() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01,
        (byte) 0x87, (byte) 0x65, (byte) 0x43, (byte) 0x21
    });
    assertEquals(0xabcdef01, data.readInt());
    assertEquals(0x87654321, data.readInt());
    assertEquals(0, data.size());
  }

  @Test public void readIntSplitAcrossMultipleSegments() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 3));
    data.write(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01
    });
    data.readUtf8(Segment.SIZE - 3);
    assertEquals(0xabcdef01, data.readInt());
    assertEquals(0, data.size());
  }

  @Test public void byteAt() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("a");
    buffer.writeUtf8(repeat('b', Segment.SIZE));
    buffer.writeUtf8("c");
    assertEquals('a', buffer.getByte(0));
    assertEquals('a', buffer.getByte(0)); // getByte doesn't mutate!
    assertEquals('c', buffer.getByte(buffer.size - 1));
    assertEquals('b', buffer.getByte(buffer.size - 2));
    assertEquals('b', buffer.getByte(buffer.size - 3));
  }

  @Test public void getByteOfEmptyBuffer() throws Exception {
    OkBuffer buffer = new OkBuffer();
    try {
      buffer.getByte(0);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void skip() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("a");
    buffer.writeUtf8(repeat('b', Segment.SIZE));
    buffer.writeUtf8("c");
    buffer.skip(1);
    assertEquals('b', buffer.readByte() & 0xff);
    buffer.skip(Segment.SIZE - 2);
    assertEquals('b', buffer.readByte() & 0xff);
    buffer.skip(1);
    assertEquals(0, buffer.size());
  }

  @Test public void testWritePrefixToEmptyBuffer() {
    OkBuffer sink = new OkBuffer();
    OkBuffer source = new OkBuffer();
    source.writeUtf8("abcd");
    sink.write(source, 2);
    assertEquals("ab", sink.readUtf8(2));
  }

  @Test public void cloneDoesNotObserveWritesToOriginal() throws Exception {
    OkBuffer original = new OkBuffer();
    OkBuffer clone = original.clone();
    original.writeUtf8("abc");
    assertEquals(0, clone.size());
  }

  @Test public void cloneDoesNotObserveReadsFromOriginal() throws Exception {
    OkBuffer original = new OkBuffer();
    original.writeUtf8("abc");
    OkBuffer clone = original.clone();
    assertEquals("abc", original.readUtf8(3));
    assertEquals(3, clone.size());
    assertEquals("ab", clone.readUtf8(2));
  }

  @Test public void originalDoesNotObserveWritesToClone() throws Exception {
    OkBuffer original = new OkBuffer();
    OkBuffer clone = original.clone();
    clone.writeUtf8("abc");
    assertEquals(0, original.size());
  }

  @Test public void originalDoesNotObserveReadsFromClone() throws Exception {
    OkBuffer original = new OkBuffer();
    original.writeUtf8("abc");
    OkBuffer clone = original.clone();
    assertEquals("abc", clone.readUtf8(3));
    assertEquals(3, original.size());
    assertEquals("ab", original.readUtf8(2));
  }

  @Test public void cloneMultipleSegments() throws Exception {
    OkBuffer original = new OkBuffer();
    original.writeUtf8(repeat('a', Segment.SIZE * 3));
    OkBuffer clone = original.clone();
    original.writeUtf8(repeat('b', Segment.SIZE * 3));
    clone.writeUtf8(repeat('c', Segment.SIZE * 3));

    assertEquals(repeat('a', Segment.SIZE * 3) + repeat('b', Segment.SIZE * 3),
        original.readUtf8(Segment.SIZE * 6));
    assertEquals(repeat('a', Segment.SIZE * 3) + repeat('c', Segment.SIZE * 3),
        clone.readUtf8(Segment.SIZE * 6));
  }

  @Test public void testEqualsAndHashCodeEmpty() throws Exception {
    OkBuffer a = new OkBuffer();
    OkBuffer b = new OkBuffer();
    assertTrue(a.equals(b));
    assertTrue(a.hashCode() == b.hashCode());
  }

  @Test public void testEqualsAndHashCode() throws Exception {
    OkBuffer a = new OkBuffer().writeUtf8("dog");
    OkBuffer b = new OkBuffer().writeUtf8("hotdog");
    assertFalse(a.equals(b));
    assertFalse(a.hashCode() == b.hashCode());

    b.readUtf8(3); // Leaves b containing 'dog'.
    assertTrue(a.equals(b));
    assertTrue(a.hashCode() == b.hashCode());
  }

  @Test public void testEqualsAndHashCodeSpanningSegments() throws Exception {
    byte[] data = new byte[1024 * 1024];
    Random dice = new Random(0);
    dice.nextBytes(data);

    OkBuffer a = bufferWithRandomSegmentLayout(dice, data);
    OkBuffer b = bufferWithRandomSegmentLayout(dice, data);
    assertTrue(a.equals(b));
    assertTrue(a.hashCode() == b.hashCode());

    data[data.length / 2]++; // Change a single byte.
    OkBuffer c = bufferWithRandomSegmentLayout(dice, data);
    assertFalse(a.equals(c));
    assertFalse(a.hashCode() == c.hashCode());
  }

  /**
   * Returns a new buffer containing the data in {@code data}, and a segment
   * layout determined by {@code dice}.
   */
  private OkBuffer bufferWithRandomSegmentLayout(Random dice, byte[] data) {
    OkBuffer result = new OkBuffer();

    // Writing to result directly will yield packed segments. Instead, write to
    // other buffers, then write those buffers to result.
    for (int pos = 0, byteCount; pos < data.length; pos += byteCount) {
      byteCount = (Segment.SIZE / 2) + dice.nextInt(Segment.SIZE / 2);
      if (byteCount > data.length - pos) byteCount = data.length - pos;
      int offset = dice.nextInt(Segment.SIZE - byteCount);

      OkBuffer segment = new OkBuffer();
      segment.write(new byte[offset]);
      segment.write(data, pos, byteCount);
      segment.skip(offset);

      result.write(segment, byteCount);
    }

    return result;
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
