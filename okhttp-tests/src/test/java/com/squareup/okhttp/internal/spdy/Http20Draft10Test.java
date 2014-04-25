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

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import okio.BufferedSource;
import okio.ByteString;
import okio.Buffer;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_END_HEADERS;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_END_STREAM;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_NONE;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_PAD_HIGH;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_PAD_LOW;
import static com.squareup.okhttp.internal.spdy.Http20Draft10.FLAG_PRIORITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http20Draft10Test {
  static final int expectedStreamId = 15;

  @Test public void unknownFrameTypeProtocolError() throws IOException {
    Buffer frame = new Buffer();

    frame.writeShort(4); // has a 4-byte field
    frame.writeByte(99); // type 99
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId);
    frame.writeInt(111111111); // custom data

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the unknown frame.
    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR: unknown frame type 99", e.getMessage());
    }
  }

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    Buffer frame = new Buffer();

    // Write the headers frame, specifying no more frames are expected.
    {
      Buffer headerBytes = literalHeaders(sentHeaders);
      frame.writeShort((int) headerBytes.size());
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(FLAG_END_HEADERS | FLAG_END_STREAM);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeAll(headerBytes);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the headers frame.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<Header> headerBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertTrue(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(sentHeaders, headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void headersWithPriority() throws IOException {
    Buffer frame = new Buffer();

    final List<Header> sentHeaders = headerEntries("name", "value");

    { // Write the headers frame, specifying priority flag and value.
      Buffer headerBytes = literalHeaders(sentHeaders);
      frame.writeShort((int) (headerBytes.size() + 4));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(FLAG_END_HEADERS | FLAG_PRIORITY);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(0); // Highest priority is 0.
      frame.writeAll(headerBytes);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the headers frame.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<Header> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(0, priority);
        assertEquals(sentHeaders, nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  /** Headers are compressed, then framed. */
  @Test public void headersFrameThenContinuation() throws IOException {

    Buffer frame = new Buffer();

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(0); // no flags
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size());
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_END_HEADERS);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeAll(headerBlock);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Reading the above frames should result in a concatenated headerBlock.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<Header> headerBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(headerEntries("foo", "barrr", "baz", "qux"), headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void pushPromise() throws IOException {
    Buffer frame = new Buffer();

    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    { // Write the push promise frame, specifying the associated stream ID.
      Buffer headerBytes = literalHeaders(pushPromise);
      frame.writeShort((int) (headerBytes.size() + 4));
      frame.writeByte(Http20Draft10.TYPE_PUSH_PROMISE);
      frame.writeByte(Http20Draft10.FLAG_END_PUSH_PROMISE);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
      frame.writeAll(headerBytes);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the headers frame.
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
    Buffer frame = new Buffer();

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
    { // Write the first headers frame.
      frame.writeShort(firstFrameLength + 4);
      frame.writeByte(Http20Draft10.TYPE_PUSH_PROMISE);
      frame.writeByte(0); // no flags
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
      frame.write(headerBlock, firstFrameLength);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort(1);
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_END_HEADERS);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, 1);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

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
    Buffer frame = new Buffer();

    frame.writeShort(4);
    frame.writeByte(Http20Draft10.TYPE_RST_STREAM);
    frame.writeByte(0); // No flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(ErrorCode.COMPRESSION_ERROR.httpCode);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the reset frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(ErrorCode.COMPRESSION_ERROR, errorCode);
      }
    });
  }

  @Test public void readSettingsFrame() throws IOException {
    Buffer frame = new Buffer();

    final int reducedTableSizeBytes = 16;

    frame.writeShort(10); // 2 settings * 1 bytes for the code and 4 for the value.
    frame.writeByte(Http20Draft10.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(1); // SETTINGS_HEADER_TABLE_SIZE
    frame.writeInt(reducedTableSizeBytes);
    frame.writeByte(2); // SETTINGS_ENABLE_PUSH
    frame.writeInt(0);

    final Http20Draft10.Reader fr = new Http20Draft10.Reader(frame, 4096, false);

    // Consume the settings frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        assertFalse(clearPrevious); // No clearPrevious in HTTP/2.
        assertEquals(reducedTableSizeBytes, settings.getHeaderTableSize());
        assertEquals(false, settings.getEnablePush(true));
      }
    });
  }

  @Test public void readSettingsFrameInvalidPushValue() throws IOException {
    Buffer frame = new Buffer();

    frame.writeShort(5); // 1 settings * 1 bytes for the code and 4 for the value.
    frame.writeByte(Http20Draft10.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(2);
    frame.writeInt(2);

    final Http20Draft10.Reader fr = new Http20Draft10.Reader(frame, 4096, false);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1", e.getMessage());
    }
  }

  @Test public void readSettingsFrameInvalidSettingId() throws IOException {
    Buffer frame = new Buffer();

    frame.writeShort(5); // 1 settings * 1 bytes for the code and 4 for the value.
    frame.writeByte(Http20Draft10.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(7); // old number for SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(1);

    final Http20Draft10.Reader fr = new Http20Draft10.Reader(frame, 4096, false);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR invalid settings id: 7", e.getMessage());
    }
  }

  @Test public void readSettingsFrameNegativeWindowSize() throws IOException {
    Buffer frame = new Buffer();

    frame.writeShort(5); // 1 settings * 1 bytes for the code and 4 for the value.
    frame.writeByte(Http20Draft10.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeByte(4); // SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(Integer.MIN_VALUE);

    final Http20Draft10.Reader fr = new Http20Draft10.Reader(frame, 4096, false);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1", e.getMessage());
    }
  }

  @Test public void pingRoundTrip() throws IOException {
    Buffer frame = new Buffer();

    final int expectedPayload1 = 7;
    final int expectedPayload2 = 8;

    // Compose the expected PING frame.
    frame.writeShort(8); // length
    frame.writeByte(Http20Draft10.TYPE_PING);
    frame.writeByte(Http20Draft10.FLAG_ACK);
    frame.writeInt(0); // connection-level
    frame.writeInt(expectedPayload1);
    frame.writeInt(expectedPayload2);

    // Check writer sends the same bytes.
    assertEquals(frame, sendPingFrame(true, expectedPayload1, expectedPayload2));

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the ping frame.
      @Override public void ping(boolean ack, int payload1, int payload2) {
        assertTrue(ack);
        assertEquals(expectedPayload1, payload1);
        assertEquals(expectedPayload2, payload2);
      }
    });
  }

  @Test public void maxLengthDataFrame() throws IOException {
    Buffer frame = new Buffer();

    final byte[] expectedData = new byte[16383];
    Arrays.fill(expectedData, (byte) 2);

    // Write the data frame.
    frame.writeShort(expectedData.length);
    frame.writeByte(Http20Draft10.TYPE_DATA);
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(expectedData);

    // Check writer sends the same bytes.
    assertEquals(frame, sendDataFrame(new Buffer().write(expectedData)));

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void data(
          boolean inFinished, int streamId, BufferedSource source, int length) throws IOException {
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

  @Test public void readPaddedDataFrame() throws IOException {
    final Buffer frame = new Buffer();

    final int dataLength = 1123;
    final byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    // Write the data frame.
    frame.writeShort(dataLength + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.write(expectedData);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertData());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedDataFrameZeroPaddingHigh() throws IOException {
    final Buffer frame = new Buffer();

    final int dataLength = 1123;
    final byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    // Write the data frame.
    frame.writeShort(dataLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0);
    frame.write(expectedData);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertData());
  }

  @Test public void readPaddedDataFrameZeroPaddingLow() throws IOException {
    final Buffer frame = new Buffer();

    final int dataLength = 1123;
    final byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    // Write the data frame.
    frame.writeShort(dataLength + 1);
    frame.writeByte(Http20Draft10.TYPE_DATA);
    frame.writeByte(FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.write(expectedData);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertData());
  }

  @Test public void readPaddedDataFrameMissingLowFlag() throws IOException {
    final Buffer frame = new Buffer();

    final int dataLength = 1123;
    final byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    // Write the data frame.
    frame.writeShort(dataLength + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_DATA);
    frame.writeByte(FLAG_PAD_HIGH);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.write(expectedData);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

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
    final Buffer frame = new Buffer();

    final int dataLength = 1123;
    final byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    final byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    // Write the data frame.
    frame.writeShort(dataLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0xffff);
    frame.write(expectedData);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR padding > 16383: 65535", e.getMessage());
    }
  }

  @Test public void readPaddedHeadersFrame() throws IOException {
    final Buffer frame = new Buffer();

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + paddingLength + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedHeadersFrameZeroPaddingHigh() throws IOException {
    final Buffer frame = new Buffer();

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0);
    frame.writeAll(headerBlock);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedHeadersFrameZeroPaddingLow() throws IOException {
    final Buffer frame = new Buffer();

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.writeAll(headerBlock);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedHeadersFrameMissingLowFlag() throws IOException {
    final Buffer frame = new Buffer();

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 1);
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

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
    final Buffer frame = new Buffer();

    final byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
    frame.writeByte(Http20Draft10.TYPE_HEADERS);
    frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeShort(0xffff);
    frame.writeAll(headerBlock);
    frame.write(padding);

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    try {
      fr.nextFrame(new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR padding > 16383: 65535", e.getMessage());
    }
  }

  /** Headers are compressed, then framed. */
  @Test public void readPaddedHeadersFrameThenContinuation() throws IOException {

    Buffer frame = new Buffer();

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2) + paddingLength + 2); // 2 for PAD_HIGH,LOW.
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeShort(paddingLength);
      frame.write(headerBlock, headerBlock.size() / 2);
      frame.write(padding);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size() + paddingLength + 2);
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION); // 2 for PAD_HIGH,LOW.
      frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeShort(paddingLength);
      frame.writeAll(headerBlock);
      frame.write(padding);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
    assertTrue(frame.exhausted()); // Padding was skipped.
  }

  @Test public void readPaddedContinuationFrameZeroPaddingHigh() throws IOException {
    final Buffer frame = new Buffer();

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(0);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_HIGH | FLAG_PAD_LOW);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeShort(0);
      frame.writeAll(headerBlock);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedContinuationFrameZeroPaddingLow() throws IOException {
    final Buffer frame = new Buffer();

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(0);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size() + 2); // 2 for PAD_HIGH,LOW.
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_END_HEADERS | FLAG_PAD_LOW);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeByte(0);
      frame.writeAll(headerBlock);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);
    fr.nextFrame(assertHeaderBlock());
  }

  @Test public void readPaddedContinuationFrameMissingLowFlag() throws IOException {
    final Buffer frame = new Buffer();

    final int paddingLength = 257;
    final byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(0);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size() + 1);
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_PAD_HIGH);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeShort(paddingLength);
      frame.writeAll(headerBlock);
      frame.write(padding);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

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
    final Buffer frame = new Buffer();

    final byte[] padding = new byte[0xffff];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft10.TYPE_HEADERS);
      frame.writeByte(0);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) (headerBlock.size() / 2) + 2); // 2 for PAD_HIGH,LOW.
      frame.writeByte(Http20Draft10.TYPE_CONTINUATION);
      frame.writeByte(FLAG_PAD_HIGH | FLAG_PAD_LOW);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeShort(0xffff);
      frame.write(headerBlock, (headerBlock.size() / 2));
      frame.write(padding);
    }

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

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
    Buffer frame = new Buffer();

    final long expectedWindowSizeIncrement = 0x7fffffff;

    // Compose the expected window update frame.
    frame.writeShort(4); // length
    frame.writeByte(Http20Draft10.TYPE_WINDOW_UPDATE);
    frame.writeByte(0); // No flags.
    frame.writeInt(expectedStreamId);
    frame.writeInt((int) expectedWindowSizeIncrement);

    // Check writer sends the same bytes.
    assertEquals(frame, windowUpdate(expectedWindowSizeIncrement));

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the window update frame.
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
    Buffer frame = new Buffer();

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    // Compose the expected GOAWAY frame without debug data.
    frame.writeShort(8); // Without debug data there's only 2 32-bit fields.
    frame.writeByte(Http20Draft10.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(expectedStreamId); // last good stream.
    frame.writeInt(expectedError.httpCode);

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the go away frame.
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(expectedStreamId, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(0, debugData.size());
      }
    });
  }

  @Test public void goAwayWithDebugDataRoundTrip() throws IOException {
    Buffer frame = new Buffer();

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;
    final ByteString expectedData = ByteString.encodeUtf8("abcdefgh");

    // Compose the expected GOAWAY frame without debug data.
    frame.writeShort(8 + expectedData.size());
    frame.writeByte(Http20Draft10.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(0); // never read any stream!
    frame.writeInt(expectedError.httpCode);
    frame.write(expectedData.toByteArray());

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(0, expectedError, expectedData.toByteArray()));

    FrameReader fr = new Http20Draft10.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the go away frame.
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertEquals(0, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(expectedData, debugData);
      }
    });
  }

  @Test public void frameSizeError() throws IOException {
    Http20Draft10.Writer writer = new Http20Draft10.Writer(new Buffer(), true);

    try {
      writer.frameHeader(0, 16384, Http20Draft10.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR length > 16383: 16384", e.getMessage());
    }
  }

  @Test public void streamIdHasReservedBit() throws IOException {
    Http20Draft10.Writer writer = new Http20Draft10.Writer(new Buffer(), true);

    try {
      int streamId = 3;
      streamId |= 1L << 31; // set reserved bit
      writer.frameHeader(streamId, 16383, Http20Draft10.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("reserved bit set: -2147483645", e.getMessage());
    }
  }

  private Buffer literalHeaders(List<Header> sentHeaders) throws IOException {
    Buffer out = new Buffer();
    new HpackDraft06.Writer(out).writeHeaders(sentHeaders);
    return out;
  }

  private Buffer sendPingFrame(boolean ack, int payload1, int payload2) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft10.Writer(out, true).ping(ack, payload1, payload2);
    return out;
  }

  private Buffer sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    Buffer out = new Buffer();
    new Http20Draft10.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out;
  }

  private Buffer sendDataFrame(Buffer data) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft10.Writer(out, true).dataFrame(expectedStreamId, FLAG_NONE, data,
        (int) data.size());
    return out;
  }

  private Buffer windowUpdate(long windowSizeIncrement) throws IOException {
    Buffer out = new Buffer();
    new Http20Draft10.Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement);
    return out;
  }

  private FrameReader.Handler assertHeaderBlock() {
    return new BaseTestHandler() {
      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<Header> headerBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(headerEntries("foo", "barrr", "baz", "qux"), headerBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    };
  }

  private FrameReader.Handler assertData() {
    return new BaseTestHandler() {
      @Override public void data(
          boolean inFinished, int streamId, BufferedSource source, int length) throws IOException {
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
}
