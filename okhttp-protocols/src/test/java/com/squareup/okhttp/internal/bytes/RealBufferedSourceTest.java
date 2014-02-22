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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class RealBufferedSourceTest {
  @Test public void inputStreamFromSource() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");
    source.writeUtf8(repeat('b', Segment.SIZE));
    source.writeUtf8("c");

    InputStream in = new RealBufferedSource(source).inputStream();
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
    InputStream in = new RealBufferedSource(source).inputStream();
    try {
      in.read(new byte[100], 50, 51);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
  }

  @Test public void requireTracksBufferFirst() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = new RealBufferedSource(source);
    bufferedSource.buffer().writeUtf8("aa");

    bufferedSource.require(2);
    assertEquals(2, bufferedSource.buffer().byteCount());
    assertEquals(2, source.byteCount());
  }

  @Test public void requireIncludesBufferBytes() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("b");

    BufferedSource bufferedSource = new RealBufferedSource(source);
    bufferedSource.buffer().writeUtf8("a");

    bufferedSource.require(2);
    assertEquals("ab", bufferedSource.buffer().readUtf8(2));
  }

  @Test public void requireInsufficientData() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");

    BufferedSource bufferedSource = new RealBufferedSource(source);

    try {
      bufferedSource.require(2);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void requireReadsOneSegmentAtATime() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE));
    source.writeUtf8(repeat('b', Segment.SIZE));

    BufferedSource bufferedSource = new RealBufferedSource(source);

    bufferedSource.require(2);
    assertEquals(Segment.SIZE, source.byteCount());
    assertEquals(Segment.SIZE, bufferedSource.buffer().byteCount());
  }

  @Test public void skipInsufficientData() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("a");

    BufferedSource bufferedSource = new RealBufferedSource(source);
    try {
      bufferedSource.skip(2);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void skipReadsOneSegmentAtATime() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8(repeat('a', Segment.SIZE));
    source.writeUtf8(repeat('b', Segment.SIZE));
    BufferedSource bufferedSource = new RealBufferedSource(source);
    bufferedSource.skip(2);
    assertEquals(Segment.SIZE, source.byteCount());
    assertEquals(Segment.SIZE - 2, bufferedSource.buffer().byteCount());
  }

  @Test public void skipTracksBufferFirst() throws Exception {
    OkBuffer source = new OkBuffer();
    source.writeUtf8("bb");

    BufferedSource bufferedSource = new RealBufferedSource(source);
    bufferedSource.buffer().writeUtf8("aa");

    bufferedSource.skip(2);
    assertEquals(0, bufferedSource.buffer().byteCount());
    assertEquals(2, source.byteCount());
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
