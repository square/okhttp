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

public final class BufferedSinkTest {
  @Test public void bytesEmittedToSinkWithFlush() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8("abc");
    bufferedSink.flush();
    assertEquals(3, sink.byteCount());
  }

  @Test public void bytesNotEmittedToSinkWithoutFlush() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8("abc");
    assertEquals(0, sink.byteCount());
  }

  @Test public void completeSegmentsEmitted() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 3));
    assertEquals(Segment.SIZE * 3, sink.byteCount());
  }

  @Test public void incompleteSegmentsNotEmitted() throws Exception {
    OkBuffer sink = new OkBuffer();
    BufferedSink bufferedSink = new BufferedSink(sink);
    bufferedSink.writeUtf8(repeat('a', Segment.SIZE * 3 - 1));
    assertEquals(Segment.SIZE * 2, sink.byteCount());
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
