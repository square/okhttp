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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class RealBufferedSinkTest {
  @Test public void outputStreamFromSink() throws Exception {
    OkBuffer sink = new OkBuffer();
    OutputStream out = new RealBufferedSink((Sink) sink).outputStream();
    out.write('a');
    out.write(repeat('b', 9998).getBytes(UTF_8));
    out.write('c');
    out.flush();
    assertEquals("a" + repeat('b', 9998) + "c", sink.readUtf8(10000));
  }

  @Test public void outputStreamFromSinkBounds() throws Exception {
    OkBuffer sink = new OkBuffer();
    OutputStream out = new RealBufferedSink((Sink) sink).outputStream();
    try {
      out.write(new byte[100], 50, 51);
      fail();
    } catch (ArrayIndexOutOfBoundsException expected) {
    }
  }

  @Test public void bufferedSinkEmitsTailWhenItIsComplete() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink((Sink) sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE - 1));
    assertEquals(0, sink.byteCount());
    bufferedSink.writeByte(0);
    assertEquals(Segment.SIZE, sink.byteCount());
    assertEquals(0, bufferedSink.buffer().byteCount());
  }

  @Test public void bufferedSinkEmitZero() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink((Sink) sink);
    bufferedSink.writeUtf8("");
    assertEquals(0, sink.byteCount());
  }

  @Test public void bufferedSinkEmitMultipleSegments() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink((Sink) sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 4 - 1));
    assertEquals(Segment.SIZE * 3, sink.byteCount());
    assertEquals(Segment.SIZE - 1, bufferedSink.buffer().byteCount());
  }

  @Test public void bufferedSinkFlush() throws IOException {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink((Sink) sink);
    bufferedSink.writeByte('a');
    assertEquals(0, sink.byteCount());
    bufferedSink.flush();
    assertEquals(0, bufferedSink.buffer().byteCount());
    assertEquals(1, sink.byteCount());
  }

  @Test public void bytesEmittedToSinkWithFlush() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink(sink);
    bufferedSink.writeUtf8("abc");
    bufferedSink.flush();
    assertEquals(3, sink.byteCount());
  }

  @Test public void bytesNotEmittedToSinkWithoutFlush() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink(sink);
    bufferedSink.writeUtf8("abc");
    assertEquals(0, sink.byteCount());
  }

  @Test public void completeSegmentsEmitted() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 3));
    assertEquals(Segment.SIZE * 3, sink.byteCount());
  }

  @Test public void incompleteSegmentsNotEmitted() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new RealBufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 3 - 1));
    assertEquals(Segment.SIZE * 2, sink.byteCount());
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
