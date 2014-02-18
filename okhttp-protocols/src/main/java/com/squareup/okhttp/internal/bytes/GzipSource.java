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
import java.util.zip.CRC32;
import java.util.zip.Inflater;

public final class GzipSource implements Source {
  private static final byte FHCRC = 1;
  private static final byte FEXTRA = 2;
  private static final byte FNAME = 3;
  private static final byte FCOMMENT = 4;

  private static final byte SECTION_HEADER = 0;
  private static final byte SECTION_BODY = 1;
  private static final byte SECTION_TRAILER = 2;
  private static final byte SECTION_DONE = 3;

  /** The current section. Always progresses forward. */
  private int section = SECTION_HEADER;

  /**
   * Our source should yield a GZIP header (which we consume directly), followed
   * by deflated bytes (which we consume via an InflaterSource), followed by a
   * GZIP trailer (which we also consume directly).
   */
  private final BufferedSource source;

  /** The inflater used to decompress the deflated body. */
  private final Inflater inflater;

  /**
   * The inflater source takes care of moving data between compressed source and
   * decompressed sink buffers.
   */
  private final InflaterSource inflaterSource;

  /** Checksum used to check both the GZIP header and decompressed body. */
  private final CRC32 crc = new CRC32();

  public GzipSource(Source source) throws IOException {
    this.inflater = new Inflater(true);
    this.source = new BufferedSource(source);
    this.inflaterSource = new InflaterSource(this.source, inflater);
  }

  @Override public long read(OkBuffer sink, long byteCount, Deadline deadline) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (byteCount == 0) return 0;

    // If we haven't consumed the header, we must consume it before anything else.
    if (section == SECTION_HEADER) {
      consumeHeader(deadline);
      section = SECTION_BODY;
    }

    // Attempt to read at least a byte of the body. If we do, we're done.
    if (section == SECTION_BODY) {
      long offset = sink.byteCount;
      long result = inflaterSource.read(sink, byteCount, deadline);
      if (result != -1) {
        updateCrc(sink, offset, result);
        return result;
      }
      section = SECTION_TRAILER;
    }

    // The body is exhausted; time to read the trailer. We always consume the
    // trailer before returning a -1 exhausted result; that way if you read to
    // the end of a GzipSource you guarantee that the CRC has been checked.
    if (section == SECTION_TRAILER) {
      consumeTrailer(deadline);
      section = SECTION_DONE;
    }

    return -1;
  }

  private void consumeHeader(Deadline deadline) throws IOException {
    // Read the 10-byte header. We peek at the flags byte first so we know if we
    // need to CRC the entire header. Then we read the magic ID1ID2 sequence.
    // We can skip everything else in the first 10 bytes.
    // +---+---+---+---+---+---+---+---+---+---+
    // |ID1|ID2|CM |FLG|     MTIME     |XFL|OS | (more-->)
    // +---+---+---+---+---+---+---+---+---+---+
    source.require(10, deadline);
    byte flags = source.buffer.getByte(3);
    boolean fhcrc = ((flags >> FHCRC) & 1) == 1;
    if (fhcrc) updateCrc(source.buffer, 0, 10);

    short id1id2 = source.readShort();
    checkEqual("ID1ID2", (short) 0x1f8b, id1id2);
    source.skip(8, deadline);

    // Skip optional extra fields.
    // +---+---+=================================+
    // | XLEN  |...XLEN bytes of "extra field"...| (more-->)
    // +---+---+=================================+
    if (((flags >> FEXTRA) & 1) == 1) {
      source.require(2, deadline);
      if (fhcrc) updateCrc(source.buffer, 0, 2);
      int xlen = source.buffer.readShortLe() & 0xffff;
      source.require(xlen, deadline);
      if (fhcrc) updateCrc(source.buffer, 0, xlen);
      source.skip(xlen, deadline);
    }

    // Skip an optional 0-terminated name.
    // +=========================================+
    // |...original file name, zero-terminated...| (more-->)
    // +=========================================+
    if (((flags >> FNAME) & 1) == 1) {
      long index = source.seek((byte) 0, deadline);
      if (fhcrc) updateCrc(source.buffer, 0, index + 1);
      source.buffer.skip(index + 1);
    }

    // Skip an optional 0-terminated comment.
    // +===================================+
    // |...file comment, zero-terminated...| (more-->)
    // +===================================+
    if (((flags >> FCOMMENT) & 1) == 1) {
      long index = source.seek((byte) 0, deadline);
      if (fhcrc) updateCrc(source.buffer, 0, index + 1);
      source.skip(index + 1, deadline);
    }

    // Confirm the optional header CRC.
    // +---+---+
    // | CRC16 |
    // +---+---+
    if (fhcrc) {
      checkEqual("FHCRC", source.readShortLe(), (short) crc.getValue());
      crc.reset();
    }
  }

  private void consumeTrailer(Deadline deadline) throws IOException {
    // Read the eight-byte trailer. Confirm the body's CRC and size.
    // +---+---+---+---+---+---+---+---+
    // |     CRC32     |     ISIZE     |
    // +---+---+---+---+---+---+---+---+
    checkEqual("CRC", source.readIntLe(), (int) crc.getValue());
    checkEqual("ISIZE", source.readIntLe(), inflater.getTotalOut());
  }

  @Override public void close(Deadline deadline) throws IOException {
    inflaterSource.close(deadline);
  }

  /** Updates the CRC with the given bytes. */
  private void updateCrc(OkBuffer buffer, long offset, long byteCount) {
    for (Segment s = buffer.head; byteCount > 0; s = s.next) {
      int segmentByteCount = s.limit - s.pos;
      if (offset < segmentByteCount) {
        int toUpdate = (int) Math.min(byteCount, segmentByteCount - offset);
        crc.update(s.data, (int) (s.pos + offset), toUpdate);
        byteCount -= toUpdate;
      }
      offset -= segmentByteCount; // Track the offset of the current segment.
    }
  }

  private void checkEqual(String name, int expected, int actual) throws IOException {
    if (actual != expected) {
      throw new IOException(String.format(
          "%s: actual 0x%08x != expected 0x%08x", name, actual, expected));
    }
  }
}
