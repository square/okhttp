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
package com.squareup.okhttp.internal.okio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;

import static com.squareup.okhttp.internal.okio.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class OkioTest {
  @Test public void sinkFromOutputStream() throws Exception {
    OkBuffer data = new OkBuffer();
    data.writeUtf8("a");
    data.writeUtf8(repeat('b', 9998));
    data.writeUtf8("c");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Sink sink = Okio.sink(out);
    sink.write(data, 3);
    assertEquals("abb", out.toString("UTF-8"));
    sink.write(data, data.size());
    assertEquals("a" + repeat('b', 9998) + "c", out.toString("UTF-8"));
  }

  @Test public void sourceFromInputStream() throws Exception {
    InputStream in = new ByteArrayInputStream(
        ("a" + repeat('b', Segment.SIZE * 2) + "c").getBytes(UTF_8));

    // Source: ab...bc
    Source source = Okio.source(in);
    OkBuffer sink = new OkBuffer();

    // Source: b...bc. Sink: abb.
    assertEquals(3, source.read(sink, 3));
    assertEquals("abb", sink.readUtf8(3));

    // Source: b...bc. Sink: b...b.
    assertEquals(Segment.SIZE, source.read(sink, 20000));
    assertEquals(repeat('b', Segment.SIZE), sink.readUtf8((int) sink.size()));

    // Source: b...bc. Sink: b...bc.
    assertEquals(Segment.SIZE - 1, source.read(sink, 20000));
    assertEquals(repeat('b', Segment.SIZE - 2) + "c", sink.readUtf8((int) sink.size()));

    // Source and sink are empty.
    assertEquals(-1, source.read(sink, 1));
  }

  @Test public void sourceFromInputStreamBounds() throws Exception {
    Source source = Okio.source(new ByteArrayInputStream(new byte[100]));
    try {
      source.read(new OkBuffer(), -1);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
