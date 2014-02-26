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
import java.util.zip.CRC32;
import org.junit.Test;

import static okio.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GzipSourceTest {

  @Test public void gunzip() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader);
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withHCRC() throws Exception {
    CRC32 hcrc = new CRC32();
    ByteString gzipHeader = gzipHeaderWithFlags((byte) 0x02);
    hcrc.update(gzipHeader.toByteArray());

    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader);
    gzipped.writeShort(Util.reverseBytesShort((short) hcrc.getValue())); // little endian
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withExtra() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x04));
    gzipped.writeShort(Util.reverseBytesShort((short) 7)); // little endian extra length
    gzipped.write("blubber".getBytes(UTF_8), 0, 7);
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withName() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x08));
    gzipped.write("foo.txt".getBytes(UTF_8), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withComment() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x10));
    gzipped.write("rubbish".getBytes(UTF_8), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  /**
   * For portability, it is a good idea to export the gzipped bytes and try running gzip.  Ex.
   * {@code echo gzipped | base64 --decode | gzip -l -v}
   */
  @Test public void gunzip_withAll() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x1c));
    gzipped.writeShort(Util.reverseBytesShort((short) 7)); // little endian extra length
    gzipped.write("blubber".getBytes(UTF_8), 0, 7);
    gzipped.write("foo.txt".getBytes(UTF_8), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write("rubbish".getBytes(UTF_8), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);
    assertGzipped(gzipped);
  }

  private void assertGzipped(OkBuffer gzipped) throws IOException {
    OkBuffer gunzipped = gunzip(gzipped);
    assertEquals("It's a UNIX system! I know this!",
        gunzipped.readUtf8((int) gunzipped.size()));
  }

  /**
   * Note that you cannot test this with old versions of gzip, as they interpret flag bit 1 as
   * CONTINUATION, not HCRC. For example, this is the case with the default gzip on osx.
   */
  @Test public void gunzipWhenHeaderCRCIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x02));
    gzipped.writeShort((short) 0); // wrong HCRC!
    gzipped.write(deflated);
    gzipped.write(gzipTrailer);

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("FHCRC: actual 0x0000261d != expected 0x00000000", e.getMessage());
    }
  }

  @Test public void gunzipWhenCRCIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader);
    gzipped.write(deflated);
    gzipped.writeInt(Util.reverseBytesInt(0x1234567)); // wrong CRC
    gzipped.write(gzipTrailer.toByteArray(), 3, 4);

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("CRC: actual 0x37ad8f8d != expected 0x01234567", e.getMessage());
    }
  }

  @Test public void gunzipWhenLengthIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader);
    gzipped.write(deflated);
    gzipped.write(gzipTrailer.toByteArray(), 0, 4);
    gzipped.writeInt(Util.reverseBytesInt(0x123456)); // wrong length

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("ISIZE: actual 0x00000020 != expected 0x00123456", e.getMessage());
    }
  }

  @Test public void gunzipExhaustsSource() throws Exception {
    OkBuffer gzippedSource = new OkBuffer()
        .write(ByteString.decodeHex("1f8b08000000000000004b4c4a0600c241243503000000")); // 'abc'

    ExhaustableSource exhaustableSource = new ExhaustableSource(gzippedSource);
    BufferedSource gunzippedSource = Okio.buffer(new GzipSource(exhaustableSource));

    assertEquals('a', gunzippedSource.readByte());
    assertEquals('b', gunzippedSource.readByte());
    assertEquals('c', gunzippedSource.readByte());
    assertFalse(exhaustableSource.exhausted);
    assertEquals(-1, gunzippedSource.read(new OkBuffer(), 1));
    assertTrue(exhaustableSource.exhausted);
  }

  @Test public void gunzipThrowsIfSourceIsNotExhausted() throws Exception {
    OkBuffer gzippedSource = new OkBuffer()
        .write(ByteString.decodeHex("1f8b08000000000000004b4c4a0600c241243503000000")); // 'abc'
    gzippedSource.writeByte('d'); // This byte shouldn't be here!

    BufferedSource gunzippedSource = Okio.buffer(new GzipSource(gzippedSource));

    assertEquals('a', gunzippedSource.readByte());
    assertEquals('b', gunzippedSource.readByte());
    assertEquals('c', gunzippedSource.readByte());
    try {
      gunzippedSource.readByte();
      fail();
    } catch (IOException expected) {
    }
  }

  private ByteString gzipHeaderWithFlags(byte flags) {
    byte[] result = gzipHeader.toByteArray();
    result[3] = flags;
    return ByteString.of(result);
  }

  private final ByteString gzipHeader = ByteString.decodeHex("1f8b0800000000000000");

  // Deflated "It's a UNIX system! I know this!"
  private final ByteString deflated = ByteString.decodeHex(
      "f32c512f56485408f5f38c5028ae2c2e49cd5554f054c8cecb2f5728c9c82c560400");

  private final ByteString gzipTrailer = ByteString.decodeHex(""
      + "8d8fad37" // Checksum of deflated.
      + "20000000" // 32 in little endian.
  );

  private OkBuffer gunzip(OkBuffer gzipped) throws IOException {
    OkBuffer result = new OkBuffer();
    GzipSource source = new GzipSource(gzipped);
    while (source.read(result, Integer.MAX_VALUE) != -1) {
    }
    return result;
  }

  /** This source keeps track of whether its read have returned -1. */
  static class ExhaustableSource implements Source {
    private final Source source;
    private boolean exhausted;

    ExhaustableSource(Source source) {
      this.source = source;
    }

    @Override public long read(OkBuffer sink, long byteCount) throws IOException {
      long result = source.read(sink, byteCount);
      if (result == -1) exhausted = true;
      return result;
    }

    @Override public Source deadline(Deadline deadline) {
      source.deadline(deadline);
      return this;
    }

    @Override public void close() throws IOException {
      source.close();
    }
  }
}
