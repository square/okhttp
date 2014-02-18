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
package com.squareup.okhttp.internal.bytes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class OkBufferTest {
  @Test public void readAndWriteUtf8() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("ab");
    assertEquals(2, buffer.byteCount());
    buffer.writeUtf8("cdef");
    assertEquals(6, buffer.byteCount());
    assertEquals("abcd", buffer.readUtf8(4));
    assertEquals(2, buffer.byteCount());
    assertEquals("ef", buffer.readUtf8(2));
    assertEquals(0, buffer.byteCount());
    try {
      buffer.readUtf8(1);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
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

  @Test public void bufferToString() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("\u0000\u0001\u0002\u007f");
    assertEquals("0001027f", buffer.toString());
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
    assertEquals(0, buffer.byteCount());
  }

  @Test public void fillAndDrainPool() throws Exception {
    OkBuffer buffer = new OkBuffer();

    // Take 2 * MAX_SIZE segments. This will drain the pool, even if other tests filled it.
    buffer.write(ByteString.of(new byte[(int) SegmentPool.MAX_SIZE]));
    buffer.write(ByteString.of(new byte[(int) SegmentPool.MAX_SIZE]));
    assertEquals(0, SegmentPool.INSTANCE.byteCount);

    // Recycle MAX_SIZE segments. They're all in the pool.
    buffer.readByteString((int) SegmentPool.MAX_SIZE);
    assertEquals(SegmentPool.MAX_SIZE, SegmentPool.INSTANCE.byteCount);

    // Recycle MAX_SIZE more segments. The pool is full so they get garbage collected.
    buffer.readByteString((int) SegmentPool.MAX_SIZE);
    assertEquals(SegmentPool.MAX_SIZE, SegmentPool.INSTANCE.byteCount);

    // Take MAX_SIZE segments to drain the pool.
    buffer.write(ByteString.of(new byte[(int) SegmentPool.MAX_SIZE]));
    assertEquals(0, SegmentPool.INSTANCE.byteCount);

    // Take MAX_SIZE more segments. The pool is drained so these will need to be allocated.
    buffer.write(ByteString.of(new byte[(int) SegmentPool.MAX_SIZE]));
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
      buffer.write(source, source.byteCount(), Deadline.NONE);
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
    sink.write(source, writeSize, Deadline.NONE);

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
    sink.write(source, writeSize, Deadline.NONE);

    assertEquals(asList(Segment.SIZE - 10, writeSize), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - writeSize, Segment.SIZE), source.segmentSizes());
  }

  @Test public void writePrefixDoesntSplit() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, 20, Deadline.NONE);

    assertEquals(asList(30), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - 20, Segment.SIZE), source.segmentSizes());
    assertEquals(30, sink.byteCount());
    assertEquals(Segment.SIZE * 2 - 20, source.byteCount());
  }

  @Test public void writePrefixDoesntSplitButRequiresCompact() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('b', Segment.SIZE - 10)); // limit = size - 10
    sink.readUtf8(Segment.SIZE - 20); // pos = size = 20

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE * 2));
    sink.write(source, 20, Deadline.NONE);

    assertEquals(asList(30), sink.segmentSizes());
    assertEquals(asList(Segment.SIZE - 20, Segment.SIZE), source.segmentSizes());
    assertEquals(30, sink.byteCount());
    assertEquals(Segment.SIZE * 2 - 20, source.byteCount());
  }

  @Test public void readExhaustedSource() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();

    assertEquals(-1, source.read(sink, 10, Deadline.NONE));
    assertEquals(10, sink.byteCount());
    assertEquals(0, source.byteCount());
  }

  @Test public void readZeroBytesFromSource() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();

    // Either 0 or -1 is reasonable here. For consistency with Android's
    // ByteArrayInputStream we return 0.
    assertEquals(-1, source.read(sink, 0, Deadline.NONE));
    assertEquals(10, sink.byteCount());
    assertEquals(0, source.byteCount());
  }

  @Test public void moveAllRequestedBytesWithRead() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('b', 15));

    assertEquals(10, source.read(sink, 10, Deadline.NONE));
    assertEquals(20, sink.byteCount());
    assertEquals(5, source.byteCount());
    assertEquals(repeat('a', 10) + repeat('b', 10), sink.readUtf8(20));
  }

  @Test public void moveFewerThanRequestedBytesWithRead() throws Exception {
    OkBuffer sink = new OkBuffer();
    sink.writeUtf8(repeat('a', 10));

    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('b', 20));

    assertEquals(20, source.read(sink, 25, Deadline.NONE));
    assertEquals(30, sink.byteCount());
    assertEquals(0, source.byteCount());
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

  @Test public void sinkFromOutputStream() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8("a");
    data.writeUtf8(repeat('b', 9998));
    data.writeUtf8("c");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Sink sink = OkBuffers.sink(out);
    sink.write(data, 3, Deadline.NONE);
    assertEquals("abb", out.toString("UTF-8"));
    sink.write(data, data.byteCount(), Deadline.NONE);
    assertEquals("a" + repeat('b', 9998) + "c", out.toString("UTF-8"));
  }

  @Test public void outputStreamFromSink() throws Exception {
    OkBuffer sink = new OkBuffer();
    OutputStream out = new BufferedSink(sink).outputStream();
    out.write('a');
    out.write(repeat('b', 9998).getBytes(UTF_8));
    out.write('c');
    out.flush();
    assertEquals("a" + repeat('b', 9998) + "c", sink.readUtf8(10000));
  }

  @Test public void outputStreamFromSinkBounds() throws Exception {
    OkBuffer sink = new OkBuffer();
    OutputStream out = new BufferedSink(sink).outputStream();
    try {
      out.write(new byte[100], 50, 51);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
  }

  @Test public void bufferedSinkEmitsTailWhenItIsComplete() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE - 1), Deadline.NONE);
    assertEquals(0, sink.byteCount());
    bufferedSink.writeByte(0, Deadline.NONE);
    assertEquals(Segment.SIZE, sink.byteCount());
    assertEquals(0, bufferedSink.buffer.byteCount());
  }

  @Test public void bufferedSinkEmitZero() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8("", Deadline.NONE);
    assertEquals(0, sink.byteCount());
  }

  @Test public void bufferedSinkEmitMultipleSegments() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 4 - 1), Deadline.NONE);
    assertEquals(Segment.SIZE * 3, sink.byteCount());
    assertEquals(Segment.SIZE - 1, bufferedSink.buffer.byteCount());
  }

  @Test public void bufferedSinkFlush() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeByte('a', Deadline.NONE);
    assertEquals(0, sink.byteCount());
    bufferedSink.flush(Deadline.NONE);
    assertEquals(0, bufferedSink.buffer.byteCount());
    assertEquals(1, sink.byteCount());
  }

  @Test public void sourceFromInputStream() throws Exception {
    InputStream in = new ByteArrayInputStream(
        ("a" + repeat('b', Segment.SIZE * 2) + "c").getBytes(UTF_8));

    // Source: ab...bc
    Source source = OkBuffers.source(in);
    OkBuffer sink = new OkBuffer();

    // Source: b...bc. Sink: abb.
    assertEquals(3, source.read(sink, 3, Deadline.NONE));
    assertEquals("abb", sink.readUtf8(3));

    // Source: b...bc. Sink: b...b.
    assertEquals(Segment.SIZE, source.read(sink, 20000, Deadline.NONE));
    assertEquals(repeat('b', Segment.SIZE), sink.readUtf8((int) sink.byteCount()));

    // Source: b...bc. Sink: b...bc.
    assertEquals(Segment.SIZE - 1, source.read(sink, 20000, Deadline.NONE));
    assertEquals(repeat('b', Segment.SIZE - 2) + "c", sink.readUtf8((int) sink.byteCount()));

    // Source and sink are empty.
    assertEquals(-1, source.read(sink, 1, Deadline.NONE));
  }

  @Test public void sourceFromInputStreamBounds() throws Exception {
    Source source = OkBuffers.source(new ByteArrayInputStream(new byte[100]));
    try {
      source.read(new OkBuffer(), -1, Deadline.NONE);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void inputStreamFromSource() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");
    source.writeUtf8(repeat('b', Segment.SIZE));
    source.writeUtf8("c");

    InputStream in = new BufferedSource(source).inputStream();
    assertEquals(0, in.available());
    assertEquals(Segment.SIZE + 2, source.byteCount());

    // Reading one byte buffers a full segment.
    assertEquals('a', in.read());
    assertEquals(Segment.SIZE - 1, in.available());
    assertEquals(2, source.byteCount());

    // Reading as much as possible reads the rest of that buffered segment.
    byte[] data = new byte[Segment.SIZE * 2];
    assertEquals(Segment.SIZE - 1, in.read(data, 0, data.length));
    assertEquals(repeat('b', Segment.SIZE - 1), new String(data, 0, Segment.SIZE - 1, UTF_8));
    assertEquals(2, source.byteCount());

    // Continuing to read buffers the next segment.
    assertEquals('b', in.read());
    assertEquals(1, in.available());
    assertEquals(0, source.byteCount());

    // Continuing to read reads from the buffer.
    assertEquals('c', in.read());
    assertEquals(0, in.available());
    assertEquals(0, source.byteCount());

    // Once we've exhausted the source, we're done.
    assertEquals(-1, in.read());
    assertEquals(0, source.byteCount());
  }

  @Test public void inputStreamFromSourceBounds() throws IOException {
    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', 100));
    InputStream in = new BufferedSource(source).inputStream();
    try {
      in.read(new byte[100], 50, 51);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
  }

  @Test public void writeBytes() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeByte(0xab);
    data.writeByte(0xcd);
    assertEquals("abcd", data.toString());
  }

  @Test public void writeLastByteInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 1));
    data.writeByte(0x20);
    data.writeByte(0x21);
    assertEquals(asList(Segment.SIZE, 1), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 1), data.readUtf8(Segment.SIZE - 1));
    assertEquals("2021", data.toString());
  }

  @Test public void writeShort() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeShort(0xabcd);
    data.writeShort(0x4321);
    assertEquals("abcd4321", data.toString());
  }

  @Test public void writeInt() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals("abcdef0187654321", data.toString());
  }

  @Test public void writeLastIntegerInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 4));
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals(asList(Segment.SIZE, 4), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 4), data.readUtf8(Segment.SIZE - 4));
    assertEquals("abcdef0187654321", data.toString());
  }

  @Test public void writeIntegerDoesntQuiteFitInSegment() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 3));
    data.writeInt(0xabcdef01);
    data.writeInt(0x87654321);
    assertEquals(asList(Segment.SIZE - 3, 8), data.segmentSizes());
    assertEquals(repeat('a', Segment.SIZE - 3), data.readUtf8(Segment.SIZE - 3));
    assertEquals("abcdef0187654321", data.toString());
  }

  @Test public void readByte() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new ByteString(new byte[] { (byte) 0xab, (byte) 0xcd }));
    assertEquals(0xab, data.readByte() & 0xff);
    assertEquals(0xcd, data.readByte() & 0xff);
    assertEquals(0, data.byteCount());
  }

  @Test public void readShort() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new ByteString(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01
    }));
    assertEquals((short) 0xabcd, data.readShort());
    assertEquals((short) 0xef01, data.readShort());
    assertEquals(0, data.byteCount());
  }

  @Test public void readShortSplitAcrossMultipleSegments() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 1));
    data.write(new ByteString(new byte[] { (byte) 0xab, (byte) 0xcd }));
    data.readUtf8(Segment.SIZE - 1);
    assertEquals((short) 0xabcd, data.readShort());
    assertEquals(0, data.byteCount());
  }

  @Test public void readInt() throws Exception {
    OkBuffer data = new OkBuffer();
    data.write(new ByteString(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01,
        (byte) 0x87, (byte) 0x65, (byte) 0x43, (byte) 0x21
    }));
    assertEquals(0xabcdef01, data.readInt());
    assertEquals(0x87654321, data.readInt());
    assertEquals(0, data.byteCount());
  }

  @Test public void readIntSplitAcrossMultipleSegments() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8(repeat('a', Segment.SIZE - 3));
    data.write(new ByteString(new byte[] {
        (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x01
    }));
    data.readUtf8(Segment.SIZE - 3);
    assertEquals(0xabcdef01, data.readInt());
    assertEquals(0, data.byteCount());
  }

  @Test public void byteAt() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("a");
    buffer.writeUtf8(repeat('b', Segment.SIZE));
    buffer.writeUtf8("c");
    assertEquals('a', buffer.getByte(0));
    assertEquals('a', buffer.getByte(0)); // getByte doesn't mutate!
    assertEquals('c', buffer.getByte(buffer.byteCount - 1));
    assertEquals('b', buffer.getByte(buffer.byteCount - 2));
    assertEquals('b', buffer.getByte(buffer.byteCount - 3));
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
    assertEquals(0, buffer.byteCount());
  }

  @Test public void testWritePrefixToEmptyBuffer() {
    OkBuffer sink = new OkBuffer();
    OkBuffer source = new OkBuffer();
    source.writeUtf8("abcd");
    sink.write(source, 2, Deadline.NONE);
    assertEquals("ab", sink.readUtf8(2));
  }

  @Test public void requireTracksBufferFirst() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = new BufferedSource(source);
    bufferedSource.buffer.writeUtf8("aa");

    bufferedSource.require(2, Deadline.NONE);
    assertEquals(2, bufferedSource.buffer.byteCount());
    assertEquals(2, source.byteCount());
  }

  @Test public void requireIncludesBufferBytes() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("b");

    BufferedSource bufferedSource = new BufferedSource(source);
    bufferedSource.buffer.writeUtf8("a");

    bufferedSource.require(2, Deadline.NONE);
    assertEquals("ab", bufferedSource.buffer.readUtf8(2));
  }

  @Test public void requireInsufficientData() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");

    BufferedSource bufferedSource = new BufferedSource(source);

    try {
      bufferedSource.require(2, Deadline.NONE);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void requireReadsOneSegmentAtATime() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE));
    source.writeUtf8(repeat('b', Segment.SIZE));

    BufferedSource bufferedSource = new BufferedSource(source);

    bufferedSource.require(2, Deadline.NONE);
    assertEquals(Segment.SIZE, source.byteCount());
    assertEquals(Segment.SIZE, bufferedSource.buffer.byteCount());
  }

  @Test public void skipInsufficientData() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");

    BufferedSource bufferedSource = new BufferedSource(source);
    try {
      bufferedSource.skip(2, Deadline.NONE);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void skipReadsOneSegmentAtATime() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE));
    source.writeUtf8(repeat('b', Segment.SIZE));
    BufferedSource bufferedSource = new BufferedSource(source);
    bufferedSource.skip(2, Deadline.NONE);
    assertEquals(Segment.SIZE, source.byteCount());
    assertEquals(Segment.SIZE - 2, bufferedSource.buffer.byteCount());
  }

  @Test public void skipTracksBufferFirst() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = new BufferedSource(source);
    bufferedSource.buffer.writeUtf8("aa");

    bufferedSource.skip(2, Deadline.NONE);
    assertEquals(0, bufferedSource.buffer.byteCount());
    assertEquals(2, source.byteCount());
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
