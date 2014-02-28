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
import com.squareup.okhttp.internal.okio.BufferedSource;
import com.squareup.okhttp.internal.okio.ByteString;
import com.squareup.okhttp.internal.okio.OkBuffer;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http20Draft09Test {
  static final int expectedStreamId = 15;

  @Test public void unknownFrameTypeIgnored() throws IOException {
    OkBuffer frame = new OkBuffer();

    frame.writeShort(4); // has a 4-byte field
    frame.writeByte(99); // type 99
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId);
    frame.writeInt(111111111); // custom data

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

    // Consume the unknown frame.
    fr.nextFrame(new BaseTestHandler());
  }

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    OkBuffer frame = new OkBuffer();

    // Write the headers frame, specifying no more frames are expected.
    {
      OkBuffer headerBytes = literalHeaders(sentHeaders);
      frame.writeShort((int) headerBytes.size());
      frame.writeByte(Http20Draft09.TYPE_HEADERS);
      frame.writeByte(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_END_STREAM);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBytes, headerBytes.size());
    }

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    final List<Header> sentHeaders = headerEntries("name", "value");

    { // Write the headers frame, specifying priority flag and value.
      OkBuffer headerBytes = literalHeaders(sentHeaders);
      frame.writeShort((int) (headerBytes.size() + 4));
      frame.writeByte(Http20Draft09.TYPE_HEADERS);
      frame.writeByte(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_PRIORITY);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(0); // Highest priority is 0.
      frame.write(headerBytes, headerBytes.size());
    }

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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

    OkBuffer frame = new OkBuffer();

    // Decoding the first header will cross frame boundaries.
    OkBuffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      frame.writeShort((int) (headerBlock.size() / 2));
      frame.writeByte(Http20Draft09.TYPE_HEADERS);
      frame.writeByte(0); // no flags
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size() / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort((int) headerBlock.size());
      frame.writeByte(Http20Draft09.TYPE_CONTINUATION);
      frame.writeByte(Http20Draft09.FLAG_END_HEADERS);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, headerBlock.size());
    }

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    { // Write the push promise frame, specifying the associated stream ID.
      OkBuffer headerBytes = literalHeaders(pushPromise);
      frame.writeShort((int) (headerBytes.size() + 4));
      frame.writeByte(Http20Draft09.TYPE_PUSH_PROMISE);
      frame.writeByte(Http20Draft09.FLAG_END_PUSH_PROMISE);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
      frame.write(headerBytes, headerBytes.size());
    }

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    // Decoding the first header will cross frame boundaries.
    OkBuffer headerBlock = literalHeaders(pushPromise);
    int firstFrameLength = (int) (headerBlock.size() - 1);
    { // Write the first headers frame.
      frame.writeShort(firstFrameLength + 4);
      frame.writeByte(Http20Draft09.TYPE_PUSH_PROMISE);
      frame.writeByte(0); // no flags
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
      frame.write(headerBlock, firstFrameLength);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      frame.writeShort(1);
      frame.writeByte(Http20Draft09.TYPE_CONTINUATION);
      frame.writeByte(Http20Draft09.FLAG_END_HEADERS);
      frame.writeInt(expectedStreamId & 0x7fffffff);
      frame.write(headerBlock, 1);
    }

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    frame.writeShort(4);
    frame.writeByte(Http20Draft09.TYPE_RST_STREAM);
    frame.writeByte(0); // No flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(ErrorCode.COMPRESSION_ERROR.httpCode);

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

    // Consume the reset frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(ErrorCode.COMPRESSION_ERROR, errorCode);
      }
    });
  }

  @Test public void readSettingsFrame() throws IOException {
    OkBuffer frame = new OkBuffer();

    final int reducedTableSizeBytes = 16;

    frame.writeShort(16); // 2 settings * 4 bytes for the code and 4 for the value.
    frame.writeByte(Http20Draft09.TYPE_SETTINGS);
    frame.writeByte(0); // No flags
    frame.writeInt(0 & 0x7fffffff); // Settings are always on the connection stream 0.
    frame.writeInt(Settings.HEADER_TABLE_SIZE & 0xffffff);
    frame.writeInt(reducedTableSizeBytes);
    frame.writeInt(Settings.ENABLE_PUSH & 0xffffff);
    frame.writeInt(0);

    final Http20Draft09.Reader fr = new Http20Draft09.Reader(frame, 4096, false);

    // Consume the settings frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        assertFalse(clearPrevious); // No clearPrevious in http/2.
        assertEquals(reducedTableSizeBytes, settings.getHeaderTableSize());
        assertEquals(false, settings.getEnablePush(true));
      }
    });
  }

  @Test public void pingRoundTrip() throws IOException {
    OkBuffer frame = new OkBuffer();

    final int expectedPayload1 = 7;
    final int expectedPayload2 = 8;

    // Compose the expected PING frame.
    frame.writeShort(8); // length
    frame.writeByte(Http20Draft09.TYPE_PING);
    frame.writeByte(Http20Draft09.FLAG_ACK);
    frame.writeInt(0); // connection-level
    frame.writeInt(expectedPayload1);
    frame.writeInt(expectedPayload2);

    // Check writer sends the same bytes.
    assertEquals(frame, sendPingFrame(true, expectedPayload1, expectedPayload2));

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() { // Consume the ping frame.
      @Override public void ping(boolean ack, int payload1, int payload2) {
        assertTrue(ack);
        assertEquals(expectedPayload1, payload1);
        assertEquals(expectedPayload2, payload2);
      }
    });
  }

  @Test public void maxLengthDataFrame() throws IOException {
    OkBuffer frame = new OkBuffer();

    final byte[] expectedData = new byte[16383];
    Arrays.fill(expectedData, (byte) 2);

    // Write the data frame.
    frame.writeShort(expectedData.length);
    frame.writeByte(Http20Draft09.TYPE_DATA);
    frame.writeByte(0); // no flags
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(expectedData);

    // Check writer sends the same bytes.
    assertEquals(frame, sendDataFrame(new OkBuffer().write(expectedData)));

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void data(
          boolean inFinished, int streamId, BufferedSource source, int length) throws IOException {
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(16383, length);
        ByteString data = source.readByteString(length);
        for (byte b : data.toByteArray()){
          assertEquals(2, b);
        }
      }
    });
  }

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new OkBuffer().write(new byte[0x1000000]));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR length > 16383: 16777216", e.getMessage());
    }
  }

  @Test public void windowUpdateRoundTrip() throws IOException {
    OkBuffer frame = new OkBuffer();

    final long expectedWindowSizeIncrement = 0x7fffffff;

    // Compose the expected window update frame.
    frame.writeShort(4); // length
    frame.writeByte(Http20Draft09.TYPE_WINDOW_UPDATE);
    frame.writeByte(0); // No flags.
    frame.writeInt(expectedStreamId);
    frame.writeInt((int) expectedWindowSizeIncrement);

    // Check writer sends the same bytes.
    assertEquals(frame, windowUpdate(expectedWindowSizeIncrement));

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    // Compose the expected GOAWAY frame without debug data.
    frame.writeShort(8); // Without debug data there's only 2 32-bit fields.
    frame.writeByte(Http20Draft09.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(expectedStreamId); // last good stream.
    frame.writeInt(expectedError.httpCode);

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    OkBuffer frame = new OkBuffer();

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;
    final ByteString expectedData = ByteString.encodeUtf8("abcdefgh");

    // Compose the expected GOAWAY frame without debug data.
    frame.writeShort(8 + expectedData.size());
    frame.writeByte(Http20Draft09.TYPE_GOAWAY);
    frame.writeByte(0); // no flags.
    frame.writeInt(0); // connection-scope
    frame.writeInt(0); // never read any stream!
    frame.writeInt(expectedError.httpCode);
    frame.write(expectedData.toByteArray());

    // Check writer sends the same bytes.
    assertEquals(frame, sendGoAway(0, expectedError, expectedData.toByteArray()));

    FrameReader fr = new Http20Draft09.Reader(frame, 4096, false);

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
    Http20Draft09.Writer writer = new Http20Draft09.Writer(new OkBuffer(), true);

    try {
      writer.frameHeader(16384, Http20Draft09.TYPE_DATA, Http20Draft09.FLAG_NONE, 0);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR length > 16383: 16384", e.getMessage());
    }
  }

  @Test public void streamIdHasReservedBit() throws IOException {
      Http20Draft09.Writer writer = new Http20Draft09.Writer(new OkBuffer(), true);

      try {
      int streamId = 3;
      streamId |= 1L << 31; // set reserved bit
      writer.frameHeader(16383, Http20Draft09.TYPE_DATA, Http20Draft09.FLAG_NONE, streamId);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("reserved bit set: -2147483645", e.getMessage());
    }
  }

  private OkBuffer literalHeaders(List<Header> sentHeaders) throws IOException {
    OkBuffer out = new OkBuffer();
    new HpackDraft05.Writer(out).writeHeaders(sentHeaders);
    return out;
  }

  private OkBuffer sendPingFrame(boolean ack, int payload1, int payload2) throws IOException {
    OkBuffer out = new OkBuffer();
    new Http20Draft09.Writer(out, true).ping(ack, payload1, payload2);
    return out;
  }

  private OkBuffer sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    OkBuffer out = new OkBuffer();
    new Http20Draft09.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out;
  }

  private OkBuffer sendDataFrame(OkBuffer data) throws IOException {
    OkBuffer out = new OkBuffer();
    new Http20Draft09.Writer(out, true).dataFrame(expectedStreamId, Http20Draft09.FLAG_NONE, data,
        (int) data.size());
    return out;
  }

  private OkBuffer windowUpdate(long windowSizeIncrement) throws IOException {
    OkBuffer out = new OkBuffer();
    new Http20Draft09.Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement);
    return out;
  }
}
