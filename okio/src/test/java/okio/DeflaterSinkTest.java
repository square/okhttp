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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class DeflaterSinkTest {
  @Test public void deflateWithClose() throws Exception {
    OkBuffer data = new OkBuffer();
    String original = "They're moving in herds. They do move in herds.";
    data.writeUtf8(original);
    OkBuffer sink = new OkBuffer();
    DeflaterSink deflaterSink = new DeflaterSink(sink, new Deflater());
    deflaterSink.write(data, data.size());
    deflaterSink.close();
    OkBuffer inflated = inflate(sink);
    assertEquals(original, inflated.readUtf8(inflated.size()));
  }

  @Test public void deflateWithSyncFlush() throws Exception {
    String original = "Yes, yes, yes. That's why we're taking extreme precautions.";
    OkBuffer data = new OkBuffer();
    data.writeUtf8(original);
    OkBuffer sink = new OkBuffer();
    DeflaterSink deflaterSink = new DeflaterSink(sink, new Deflater());
    deflaterSink.write(data, data.size());
    deflaterSink.flush();
    OkBuffer inflated = inflate(sink);
    assertEquals(original, inflated.readUtf8(inflated.size()));
  }

  @Test public void deflateWellCompressed() throws IOException {
    String original = repeat('a', 1024 * 1024);
    OkBuffer data = new OkBuffer();
    data.writeUtf8(original);
    OkBuffer sink = new OkBuffer();
    DeflaterSink deflaterSink = new DeflaterSink(sink, new Deflater());
    deflaterSink.write(data, data.size());
    deflaterSink.close();
    OkBuffer inflated = inflate(sink);
    assertEquals(original, inflated.readUtf8(inflated.size()));
  }

  @Test public void deflatePoorlyCompressed() throws IOException {
    ByteString original = randomBytes(1024 * 1024);
    OkBuffer data = new OkBuffer();
    data.write(original);
    OkBuffer sink = new OkBuffer();
    DeflaterSink deflaterSink = new DeflaterSink(sink, new Deflater());
    deflaterSink.write(data, data.size());
    deflaterSink.close();
    OkBuffer inflated = inflate(sink);
    assertEquals(original, inflated.readByteString(inflated.size()));
  }

  @Test public void multipleSegmentsWithoutCompression() throws IOException {
    OkBuffer buffer = new OkBuffer();
    Deflater deflater = new Deflater();
    deflater.setLevel(Deflater.NO_COMPRESSION);
    DeflaterSink deflaterSink = new DeflaterSink(buffer, deflater);
    int byteCount = Segment.SIZE * 4;
    deflaterSink.write(new OkBuffer().writeUtf8(repeat('a', byteCount)), byteCount);
    deflaterSink.close();
    assertEquals(repeat('a', byteCount), inflate(buffer).readUtf8(byteCount));
  }

  /**
   * This test deflates a single segment of without compression because that's
   * the easiest way to force close() to emit a large amount of data to the
   * underlying sink.
   */
  @Test public void closeWithExceptionWhenWritingAndClosing() throws IOException {
    MockSink mockSink = new MockSink();
    mockSink.scheduleThrow(0, new IOException("first"));
    mockSink.scheduleThrow(1, new IOException("second"));
    Deflater deflater = new Deflater();
    deflater.setLevel(Deflater.NO_COMPRESSION);
    DeflaterSink deflaterSink = new DeflaterSink(mockSink, deflater);
    deflaterSink.write(new OkBuffer().writeUtf8(repeat('a', Segment.SIZE)), Segment.SIZE);
    try {
      deflaterSink.close();
      fail();
    } catch (IOException expected) {
      assertEquals("first", expected.getMessage());
    }
    mockSink.assertLogContains("close()");
  }

  /**
   * Uses streaming decompression to inflate {@code deflated}. The input must
   * either be finished or have a trailing sync flush.
   */
  private OkBuffer inflate(OkBuffer deflated) throws IOException {
    InputStream deflatedIn = deflated.inputStream();
    Inflater inflater = new Inflater();
    InputStream inflatedIn = new InflaterInputStream(deflatedIn, inflater);
    OkBuffer result = new OkBuffer();
    byte[] buffer = new byte[8192];
    while (!inflater.needsInput() || deflated.size() > 0 || deflatedIn.available() > 0) {
      int count = inflatedIn.read(buffer, 0, buffer.length);
      result.write(buffer, 0, count);
    }
    return result;
  }

  private ByteString randomBytes(int length) {
    Random random = new Random(0);
    byte[] randomBytes = new byte[length];
    random.nextBytes(randomBytes);
    return ByteString.of(randomBytes);
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
