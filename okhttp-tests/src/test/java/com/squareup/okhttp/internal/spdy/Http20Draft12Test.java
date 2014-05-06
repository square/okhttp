/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.GzipSink;
import okio.Okio;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_COMPRESSED;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_END_HEADERS;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_END_STREAM;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_NONE;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_PAD_HIGH;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_PAD_LOW;
import static com.squareup.okhttp.internal.spdy.Http20Draft12.FLAG_PRIORITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http20Draft12Test {
  final Buffer frame = new Buffer();
  final FrameReader fr = new Http20Draft12.Reader(frame, 4096, false);
  final int expectedStreamId = 15;

  @Test public void unknownFrameTypeProtocolError() throws IOException {
    frame.writeShort(4); // has a 4-byte field
    frame.writeByte(99); // type 99
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId);
    frame.writeInt(111111111); // custom data

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR: unknown frame type 99", e.getMessage());
    }
  }

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    Buffer headerBytes = literalHeaders(sentHeaders);
    frame.writeShort((int) headerBytes.size());
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_END_STREAM);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBytes);

    fr.nextFrame(new BaseTestHandler() {
      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock, HeadersMode headersMode) {
        assertFalse(outFinished);
        assertTrue(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(sentHeaders, headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void headersWithPriority() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    Buffer headerBytes = literalHeaders(sentHeaders);
    frame.writeShort((int) (headerBytes.size() + 5));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PRIORITY);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(0); // Independent stream.
    frame.writeByte(255); // Heaviest weight, zero-indexed.
    frame.writeAll(headerBytes);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void priority(int streamId, int streamDependency, int weight,
          boolean exclusive) {
        assertEquals(0, streamDependency);
        assertEquals(256, weight);
        assertFalse(exclusive);
      }

      @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, List<Header> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(sentHeaders, nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  /** Headers are compressed, then framed. */
  @Test public void headersFrameThenContinuation() throws IOException {
    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, headerBlock.size() / 2);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) headerBlock.size());
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBlock);

    // Reading the above frames should result in a concatenated headerBlock.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock, HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(headerEntries("foo", "barrr", "baz", "qux"), headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void pushPromise() throws IOException {
    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    // Write the push promise frame, specifying the associated stream ID.
    Buffer headerBytes = literalHeaders(pushPromise);
    frame.writeShort((int) (headerBytes.size() + 4));
    frame.writeByte(Http20Draft12.TYPE_PUSH_PROMISE);
    frame.writeByte(Http20Draft12.FLAG_END_PUSH_PROMISE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
    frame.writeAll(headerBytes);

    fr.nextFrame(new BaseTestHandler() {
      @Override
      public void pushPromise(int streamId, int promisedStreamId, List<Header> headerBlock) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(expectedPromisedStreamId, promisedStreamId);
        assertEquals(pushPromise, headerBlock);
      }
    });
  }

  /** Headers are compressed, then framed. */
  @Test public void pushPromiseThenContinuation() throws IOException {
    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(pushPromise);
    int firstFrameLength = (int) (headerBlock.size() - 1);
    // Write the first headers frame.
    frame.writeShort(firstFrameLength + 4);
    frame.writeByte(Http20Draft12.TYPE_PUSH_PROMISE);
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
    frame.write(headerBlock, firstFrameLength);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort(1);
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, 1);

    // Reading the above frames should result in a concatenated headerBlock.
    fr.nextFrame(new BaseTestHandler() {
      @Override
      public void pushPromise(int streamId, int promisedStreamId, List<Header> headerBlock) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(expectedPromisedStreamId, promisedStreamId);
        assertEquals(pushPromise, headerBlock);
      }
    });
  }

  @Test public void readRstStreamFrame() throws IOException {
    frame.writeShort(4);
    frame.writeByte(Http20Draft12.TYPE_RST_STREAM);
    frame.writeByte(0); // No flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(ErrorCode.COMPRESSION_ERROR.httpCode);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(ErrorCode.COMPRESSION_ERROR, errorCode);
      }
    });
  }

  @Test public void readSettingsFrame() throws IOException {
    final int reducedTableSizeBytes = 16;

    frame.writeShort(15); // 3 settings * 5 bytes (1 for the code and 4 for the value).
    frame.writeByte(Http20Draft12.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(1); // SETTINGS_HEADER_TABLE_SIZE
    frame.writeInt(reducedTableSizeBytes);
    frame.writeByte(2); // SETTINGS_ENABLE_PUSH
    frame.writeInt(0);
    frame.writeByte(5); // SETTINGS_COMPRESS_DATA
    frame.writeInt(0);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        assertFalse(clearPrevious); // No clearPrevious in HTTP/2.
        assertEquals(reducedTableSizeBytes, settings.getHeaderTableSize());
        assertEquals(false, settings.getEnablePush(true));
        assertEquals(false, settings.getCompressData(true));
      }
    });
  }

  @Test public void readSettingsFrameInvalidPushValue() throws IOException {
    frame.writeShort(5); // 1 for the code and 4 for the value
    frame.writeByte(Http20Draft12.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(2);
    frame.writeInt(2);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1", e.getMessage());
    }
  }

  @Test public void readSettingsFrameInvalidSettingId() throws IOException {
    frame.writeShort(5); // 1 for the code and 4 for the value
    frame.writeByte(Http20Draft12.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(7); // old number for SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(1);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR invalid settings id: 7", e.getMessage());
    }
  }

  @Test public void readSettingsFrameNegativeWindowSize() throws IOException {
    frame.writeShort(5); // 1 for the code and 4 for the value
    frame.writeByte(Http20Draft12.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(4); // SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(Integer.MIN_VALUE);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1", e.getMessage());
    }
  }

  @Test public void pingRoundTrip() throws IOException {
    final int expectedPayload1 = 7;
    final int expectedPayload2 = 8;

    frame.writeShort(8); // length
    frame.writeByte(Http20Draft12.TYPE_PING);
    frame.writeByte(Http20Draft12.FLAG_ACK);
    frame.writeInt(0); // connection-level
    frame.writeInt(expectedPayload1);
    frame.writeInt(expectedPayload2);

    // Check writer sends the same bytes.
    assertEquals(frame, sendPingFrame(true, expectedPayload1, expectedPayload2));

    fr.nextFrame(new BaseTestHandler() {
      @Override public void ping(boolean ack, int payload1, int payload2) {
        assertTrue(ack);
        assertEquals(expectedPayload1, payload1);
        assertEquals(expectedPayload2, payload2);
      }
    });
  }

  @Test public void maxLengthDataFrame() throws IOException {
    final byte[] expectedData = new byte[16383];
    Arrays.fill(expectedData, (byte) 2);

    frame.writeShort(expectedData.length);
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(expectedData);

    // Check writer sends the same bytes.
    assertEquals(frame, sendDataFrame(new Buffer().write(expectedData)));

    fr.nextFrame(new BaseTestHandler() {
      @Override public void data(boolean inFinished, int streamId, BufferedSource source,
          int length) throws IOException {
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(16383, length);
        ByteString data = source.readByteString(length);
        for (byte b : data.toByteArray()) {
          assertEquals(2, b);
        }
      }
    });
  }

  /** We do not send SETTINGS_COMPRESS_DATA = 1, nor want to. Let's make sure we error. */
  @Test public void compressedDataFrameWhenSettingDisabled() throws IOException {
    byte[] expectedData = new byte[16383];
    Arrays.fill(expectedData, (byte) 2);
    Buffer zipped = gzip(expectedData);
    int zippedSize = (int) zipped.size();

    frame.writeShort(zippedSize);
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(FLAG_COMPRESSED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    zipped.readAll(frame);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA",
          e.getMessage());
    }
  }

  @Test public void readPaddedDataFrame() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    frame.writeShort(dataLength + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.write(expectedData);
    frame.write(padding);

    fr.nextFrame(assertData());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedDataFrameZeroPaddingHigh() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    frame.writeShort(dataLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0);
    frame.write(expectedData);

    fr.nextFrame(assertData());
  }

  @Test public void readPaddedDataFrameZeroPaddingLow() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    frame.writeShort(dataLength + 1);
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.write(expectedData);

    fr.nextFrame(assertData());
  }

  @Test public void readPaddedDataFrameMissingLowFlag() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    frame.writeShort(dataLength + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.write(expectedData);
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR FLAG_PAD_HIGH set without FLAG_PAD_LOW", e.getMessage());
    }
  }

  /**
   * Padding is encoded over 2 bytes, so maximum value is 65535, but maximum frame size is 16383.
   */
  @Test public void readPaddedDataFrameWithTooMuchPadding() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    final byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    frame.writeShort(dataLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0xffff);
    frame.write(expectedData);
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR padding > 16383: 65535", e.getMessage());
    }
  }

  @Test public void readPaddedHeadersFrame() throws IOException {
    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    fr.nextFrame(assertHeaderBlock());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedHeadersFrameZeroPaddingHigh() throws IOException {
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0);
    frame.writeAll(headerBlock);

    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedHeadersFrameZeroPaddingLow() throws IOException {
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.writeAll(headerBlock);

    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedHeadersFrameMissingLowFlag() throws IOException {
    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 1);
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR FLAG_PAD_HIGH set without FLAG_PAD_LOW", e.getMessage());
    }
  }

  /**
   * Padding is encoded over 2 bytes, so maximum value is 65535, but maximum frame size is 16383.
   */
  @Test public void readPaddedHeadersFrameWithTooMuchPadding() throws IOException {
    byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0xffff);
    frame.writeAll(headerBlock);
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR padding > 16383: 65535", e.getMessage());
    }
  }

  /** Headers are compressed, then framed. */
  @Test public void readPaddedHeadersFrameThenContinuation() throws IOException {
    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2) + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.write(headerBlock, headerBlock.size() / 2);
    frame.write(padding);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) headerBlock.size() + paddingLength + 2);
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION); // 2 for PAD_HIGH,LOW.
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    fr.nextFrame(assertHeaderBlock());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedContinuationFrameZeroPaddingHigh() throws IOException {
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(0);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, headerBlock.size() / 2);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0);
    frame.writeAll(headerBlock);

    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedContinuationFrameZeroPaddingLow() throws IOException {
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(0);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, headerBlock.size() / 2);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.writeAll(headerBlock);

    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedContinuationFrameMissingLowFlag() throws IOException {
    int paddingLength = 257;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(0);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, headerBlock.size() / 2);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) headerBlock.size() + 1);
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_PAD_HIGH);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR FLAG_PAD_HIGH set without FLAG_PAD_LOW", e.getMessage());
    }
  }

  /**
   * Padding is encoded over 2 bytes, so maximum value is 65535, but maximum frame size is 16383.
   */
  @Test public void readPaddedContinuationFrameWithTooMuchPadding() throws IOException {
    byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    // Write the first headers frame.
    frame.writeShort((int) (headerBlock.size() / 2));
    frame.writeByte(Http20Draft12.TYPE_HEADERS);
    frame.writeByte(0);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, headerBlock.size() / 2);

    // Write the continuation frame, specifying no more frames are expected.
    frame.writeShort((int) (headerBlock.size() / 2) + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft12.TYPE_CONTINUATION);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0xffff);
    frame.write(headerBlock, (headerBlock.size() / 2));
    frame.write(padding);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR padding > 16383: 65535", e.getMessage());
    }
  }

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new Buffer().write(new byte[0x1000000]));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR length > 16383: 16777216", e.getMessage());
    }
  }

  @Test public void windowUpdateRoundTrip() throws IOException {
    final long expectedWindowSizeIncrement = 0x7fffffff;

    frame.writeShort(4); // length
    frame.writeByte(Http20Draft12.TYPE_WINDOW_UPDATE);
    frame.writeByte(0); // No flags.
    frame.writeInt(expectedStreamId);
    frame.writeInt((int) expectedWindowSizeIncrement);

    // Check writer sends the same bytes.
    assertEquals(frame, windowUpdate(expectedWindowSizeIncrement));

    fr.nextFrame(new BaseTestHandler() {
      @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(expectedWindowSizeIncrement, windowSizeIncrement);
      }
    });
  }

  @Test public void badWindowSizeIncrement() throws IOException {
    try {
      windowUpdate(0);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 0",
          e.getMessage());
    }
    try {
      windowUpdate(0x80000000L);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 2147483648",
          e.getMessage());
    }
  }

  @Test public void goAwayWithoutDebugDataRoundTrip() throws IOException {
    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    frame.writeShort(8); // Without debug data there's only 2 32-bit fields.
    frame.writeByte(Http20Draft12.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(expectedStreamId); // last good stream.
    frame.writeInt(expectedError.httpCode);

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    fr.nextFrame(new BaseTestHandler() {
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(expectedStreamId, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(0, debugData.size());
      }
    });
  }

  @Test public void goAwayWithDebugDataRoundTrip() throws IOException {
    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;
    final ByteString expectedData = ByteString.encodeUtf8("abcdefgh");

    // Compose the expected GOAWAY frame without debug data.
    frame.writeShort(8 + expectedData.size());
    frame.writeByte(Http20Draft12.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(0); // never read any stream!
    frame.writeInt(expectedError.httpCode);
    frame.write(expectedData.toByteArray());

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(0, expectedError, expectedData.toByteArray()));

    fr.nextFrame(new BaseTestHandler() {
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(0, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(expectedData, debugData);
      }
    });
  }

  @Test public void frameSizeError() throws IOException {
    Http20Draft12.Writer writer = new Http20Draft12.Writer(new Buffer(), true);

    try {
      writer.frameHeader(0, 16384, Http20Draft12.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR length > 16383: 16384", e.getMessage());
    }
  }

  @Test public void streamIdHasReservedBit() throws IOException {
    Http20Draft12.Writer writer = new Http20Draft12.Writer(new Buffer(), true);

    try {
      int streamId = 3;
      streamId |= 1L << 31; // set reserved bit
      writer.frameHeader(streamId, 16383, Http20Draft12.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("reserved bit set: -2147483645", e.getMessage());
    }
  }

  @Test public void blockedFrameIgnored() throws IOException {
    frame.writeShort(0);
    frame.writeByte(Http20Draft12.TYPE_BLOCKED);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope.

    fr.nextFrame(new BaseTestHandler()); // Should not callback.
  }

  @Test public void blockedFrameIOEWithPayload() throws IOException {
    frame.writeShort(4);
    frame.writeByte(Http20Draft12.TYPE_BLOCKED);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope.
    frame.writeUtf8("abcd"); // Send a payload even though it is illegal.

    // Consume the unknown frame.
    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("TYPE_BLOCKED length != 0: 4", e.getMessage());
    }
  }

  @Test public void readAltSvcStreamOrigin() throws IOException {
    frame.writeShort(9 + Protocol.HTTP_2.toString().length() + "www-2.example.com".length());
    frame.writeByte(Http20Draft12.TYPE_ALTSVC);
    frame.writeByte(0); // No flags.
    frame.writeInt(expectedStreamId); // Use stream origin.
    frame.writeInt(0xffffffff); // Max-Age 32bit number.
    frame.writeShort(443); // Port.
    frame.writeByte(0); // Reserved.
    frame.writeByte(Protocol.HTTP_2.toString().length()); // Proto-Len.
    frame.writeUtf8(Protocol.HTTP_2.toString()); // Protocol-ID.
    frame.writeByte("www-2.example.com".length()); // Host-Len.
    frame.writeUtf8("www-2.example.com");

    fr.nextFrame(new BaseTestHandler() { // Consume the alt-svc frame.
      @Override public void alternateService(int streamId, String origin, ByteString protocol,
          String host, int port, long maxAge) {
        assertEquals(expectedStreamId, streamId);
        assertEquals("", origin);
        assertEquals(Protocol.HTTP_2.toString(), protocol.utf8());
        assertEquals("www-2.example.com", host);
        assertEquals(443, port);
        assertEquals(0xffffffffL, maxAge);
      }
    });
  }

  @Test public void readAltSvcAlternateOrigin() throws IOException {
    frame.writeShort(9
        + Protocol.HTTP_2.toString().length()
        + "www-2.example.com".length()
        + "https://example.com:443".length());
    frame.writeByte(Http20Draft12.TYPE_ALTSVC);
    frame.writeByte(0); // No flags.
    frame.writeInt(0); // Specify origin.
    frame.writeInt(0xffffffff); // Max-Age 32bit number.
    frame.writeShort(443); // Port.
    frame.writeByte(0); // Reserved.
    frame.writeByte(Protocol.HTTP_2.toString().length()); // Proto-Len.
    frame.writeUtf8(Protocol.HTTP_2.toString()); // Protocol-ID.
    frame.writeByte("www-2.example.com".length()); // Host-Len.
    frame.writeUtf8("www-2.example.com");
    frame.writeUtf8("https://example.com:443"); // Remainder is Origin.

    fr.nextFrame(new BaseTestHandler() { // Consume the alt-svc frame.
      @Override public void alternateService(int streamId, String origin, ByteString protocol,
          String host, int port, long maxAge) {
        assertEquals(0, streamId);
        assertEquals("https://example.com:443", origin);
        assertEquals(Protocol.HTTP_2.toString(), protocol.utf8());
        assertEquals("www-2.example.com", host);
        assertEquals(443, port);
        assertEquals(0xffffffffL, maxAge);
      }
    });
  }

  private Buffer literalHeaders(List<Header> sentHeaders) throws IOException {
    Buffer out = new Buffer();
    new HpackDraft07.Writer(out).writeHeaders(sentHeaders);
    return out;
  }

  private Buffer sendPingFrame(boolean ack, int payload1, int payload2) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft12.Writer(out, true).ping(ack, payload1, payload2);
    return out;
  }

  private Buffer sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    Buffer out = new Buffer();
    new Http20Draft12.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out;
  }

  private Buffer sendDataFrame(Buffer data) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft12.Writer(out, true).dataFrame(expectedStreamId, FLAG_NONE, data,
        (int) data.size());
    return out;
  }

  private Buffer windowUpdate(long windowSizeIncrement) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft12.Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement);
    return out;
  }

  private FrameReader.Handler assertHeaderBlock() {
    return new BaseTestHandler() {
      @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock, HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(headerEntries("foo", "barrr", "baz", "qux"), headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    };
  }

  private FrameReader.Handler assertData() {
    return new BaseTestHandler() {
      @Override public void data(boolean inFinished, int streamId, BufferedSource source,
          int length) throws IOException {
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(1123, length);
        ByteString data = source.readByteString(length);
        for (byte b : data.toByteArray()) {
          assertEquals(2, b);
        }
      }
    };
  }

  private static Buffer gzip(byte[] data) throws IOException {
    Buffer buffer = new Buffer();
    Okio.buffer(new GzipSink(buffer)).write(data).close();
    return buffer;
  }
}
