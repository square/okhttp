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

import java.util.Arrays;
import org.junit.Test;

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
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void bufferToString() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8("\u0000\u0001\u0002\u007f");
    assertEquals("0001027f", buffer.toString());
  }

  @Test public void multipleSegmentBuffers() throws Exception {
    OkBuffer buffer = new OkBuffer();
    buffer.writeUtf8(repeat('a',  1000));
    buffer.writeUtf8(repeat('b',  2500));
    buffer.writeUtf8(repeat('c',  5000));
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

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
