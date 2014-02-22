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

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GzipSourceTest {

  @Test public void gunzip() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader, 0, gzipHeader.length);
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withHCRC() throws Exception {
    CRC32 hcrc = new CRC32();
    hcrc.update(gzipHeaderWithFlags((byte) 0x02));

    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x02), 0, gzipHeader.length);
    gzipped.writeShort(Util.reverseBytesShort((short) hcrc.getValue())); // little endian
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withExtra() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x04), 0, gzipHeader.length);
    gzipped.writeShort(Util.reverseBytesShort((short) 7)); // little endian extra length
    gzipped.write("blubber".getBytes(Util.US_ASCII), 0, 7);
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withName() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x08), 0, gzipHeader.length);
    gzipped.write("foo.txt".getBytes(Util.US_ASCII), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  @Test public void gunzip_withComment() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x10), 0, gzipHeader.length);
    gzipped.write("rubbish".getBytes(Util.US_ASCII), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  /**
   * For portability, it is a good idea to export the gzipped bytes and try running gzip.  Ex.
   * {@code echo gzipped | base64 --decode | gzip -l -v}
   */
  @Test public void gunzip_withAll() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x1c), 0, gzipHeader.length);
    gzipped.writeShort(Util.reverseBytesShort((short) 7)); // little endian extra length
    gzipped.write("blubber".getBytes(Util.US_ASCII), 0, 7);
    gzipped.write("foo.txt".getBytes(Util.US_ASCII), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write("rubbish".getBytes(Util.US_ASCII), 0, 7);
    gzipped.writeByte(0); // zero-terminated
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);
    assertGzipped(gzipped);
  }

  private void assertGzipped(OkBuffer gzipped) throws IOException {
    OkBuffer gunzipped = gunzip(gzipped);
    assertEquals("It's a UNIX system! I know this!",
        gunzipped.readUtf8((int) gunzipped.byteCount()));
  }

  /**
   * Note that you cannot test this with old versions of gzip, as they interpret flag bit 1 as
   * CONTINUATION, not HCRC. For example, this is the case with the default gzip on osx.
   */
  @Test public void gunzipWhenHeaderCRCIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeaderWithFlags((byte) 0x02), 0, gzipHeader.length);
    gzipped.writeShort((short) 0); // wrong HCRC!
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, gzipTrailer.length);

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("FHCRC: actual 0x0000261d != expected 0x00000000", e.getMessage());
    }
  }

  @Test public void gunzipWhenCRCIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader, 0, gzipHeader.length);
    gzipped.write(deflated, 0, deflated.length);
    gzipped.writeInt(Util.reverseBytesInt(0x1234567)); // wrong CRC
    gzipped.write(gzipTrailer, 3, 4);

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("CRC: actual 0x37ad8f8d != expected 0x01234567", e.getMessage());
    }
  }

  @Test public void gunzipWhenLengthIncorrect() throws Exception {
    OkBuffer gzipped = new OkBuffer();
    gzipped.write(gzipHeader, 0, gzipHeader.length);
    gzipped.write(deflated, 0, deflated.length);
    gzipped.write(gzipTrailer, 0, 4);
    gzipped.writeInt(Util.reverseBytesInt(0x123456)); // wrong length

    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("ISIZE: actual 0x00000020 != expected 0x00123456", e.getMessage());
    }
  }

  private byte[] gzipHeaderWithFlags(byte flags) {
    byte[] result = Arrays.copyOf(gzipHeader, gzipHeader.length);
    result[3] = flags;
    return result;
  }

  private final byte[] gzipHeader = new byte[] {
      (byte) 0x1f, (byte) 0x8b, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0
  };

  // deflated "It's a UNIX system! I know this!"
  private final byte[] deflated = new byte[] {
      (byte) 0xf3, (byte) 0x2c, (byte) 0x51, (byte) 0x2f, (byte) 0x56, (byte) 0x48, (byte) 0x54,
      (byte) 0x08, (byte) 0xf5, (byte) 0xf3, (byte) 0x8c, (byte) 0x50, (byte) 0x28, (byte) 0xae,
      (byte) 0x2c, (byte) 0x2e, (byte) 0x49, (byte) 0xcd, (byte) 0x55, (byte) 0x54, (byte) 0xf0,
      (byte) 0x54, (byte) 0xc8, (byte) 0xce, (byte) 0xcb, (byte) 0x2f, (byte) 0x57, (byte) 0x28,
      (byte) 0xc9, (byte) 0xc8, (byte) 0x2c, (byte) 0x56, (byte) 0x04, (byte) 0x00
  };

  private final byte[] gzipTrailer = new byte[] {
      (byte) 0x8d, (byte) 0x8f, (byte) 0xad, (byte) 0x37, // checksum of deflated
      0x20, 0, 0, 0, // 32 in little endian
  };

  private OkBuffer gunzip(OkBuffer gzipped) throws IOException {
    OkBuffer result = new OkBuffer();
    GzipSource source = new GzipSource(gzipped);
    while (source.read(result, Integer.MAX_VALUE) != -1) {
    }
    return result;
  }
}
