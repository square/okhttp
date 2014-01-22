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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http20Draft09Test {
  static final int expectedStreamId = 15;

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Write the headers frame, specifying no more frames are expected.
    {
      byte[] headerBytes = literalHeaders(sentHeaders);
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_HEADERS);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_END_STREAM);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    FrameReader fr = newReader(out);

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
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final List<Header> sentHeaders = headerEntries("name", "value");

    { // Write the headers frame, specifying priority flag and value.
      byte[] headerBytes = literalHeaders(sentHeaders);
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_HEADERS);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_PRIORITY);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.writeInt(0); // Highest priority is 0.
      dataOut.write(headerBytes);
    }

    FrameReader fr = newReader(out);

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

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Decoding the first header will cross frame boundaries.
    byte[] headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    { // Write the first headers frame.
      dataOut.writeShort(headerBlock.length / 2);
      dataOut.write(Http20Draft09.TYPE_HEADERS);
      dataOut.write(0); // no flags
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBlock, 0, headerBlock.length / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      dataOut.writeShort(headerBlock.length / 2);
      dataOut.write(Http20Draft09.TYPE_CONTINUATION);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBlock, headerBlock.length / 2, headerBlock.length / 2);
    }

    FrameReader fr = newReader(out);

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
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    { // Write the push promise frame, specifying the associated stream ID.
      byte[] headerBytes = literalHeaders(pushPromise);
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_PUSH_PROMISE);
      dataOut.write(Http20Draft09.FLAG_END_PUSH_PROMISE);
      dataOut.writeInt(expectedStreamId & 0x7fffffff);
      dataOut.writeInt(expectedPromisedStreamId & 0x7fffffff);
      dataOut.write(headerBytes);
    }

    FrameReader fr = newReader(out);

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
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    // Decoding the first header will cross frame boundaries.
    byte[] headerBlock = literalHeaders(pushPromise);
    { // Write the first headers frame.
      dataOut.writeShort(headerBlock.length / 2);
      dataOut.write(Http20Draft09.TYPE_PUSH_PROMISE);
      dataOut.write(0); // no flags
      dataOut.writeInt(expectedStreamId & 0x7fffffff);
      dataOut.writeInt(expectedPromisedStreamId & 0x7fffffff);
      dataOut.write(headerBlock, 0, headerBlock.length / 2);
    }

    { // Write the continuation frame, specifying no more frames are expected.
      dataOut.writeShort(headerBlock.length / 2);
      dataOut.write(Http20Draft09.TYPE_CONTINUATION);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS);
      dataOut.writeInt(expectedStreamId & 0x7fffffff);
      dataOut.write(headerBlock, headerBlock.length / 2, headerBlock.length / 2);
    }

    FrameReader fr = newReader(out);

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
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    dataOut.writeShort(4);
    dataOut.write(Http20Draft09.TYPE_RST_STREAM);
    dataOut.write(0); // No flags
    dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
    dataOut.writeInt(ErrorCode.COMPRESSION_ERROR.httpCode);

    FrameReader fr = newReader(out);

    // Consume the reset frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(ErrorCode.COMPRESSION_ERROR, errorCode);
      }
    });
  }

  @Test public void readSettingsFrame() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final int reducedTableSizeBytes = 16;

    dataOut.writeShort(16); // 2 settings * 4 bytes for the code and 4 for the value.
    dataOut.write(Http20Draft09.TYPE_SETTINGS);
    dataOut.write(0); // No flags
    dataOut.writeInt(0 & 0x7fffffff); // Settings are always on the connection stream 0.
    dataOut.writeInt(Settings.HEADER_TABLE_SIZE & 0xffffff);
    dataOut.writeInt(reducedTableSizeBytes);
    dataOut.writeInt(Settings.ENABLE_PUSH & 0xffffff);
    dataOut.writeInt(0);

    final Http20Draft09.Reader fr = newReader(out);

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
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final int expectedPayload1 = 7;
    final int expectedPayload2 = 8;

    // Compose the expected PING frame.
    dataOut.writeShort(8); // length
    dataOut.write(Http20Draft09.TYPE_PING);
    dataOut.write(Http20Draft09.FLAG_ACK);
    dataOut.writeInt(0); // connection-level
    dataOut.writeInt(expectedPayload1);
    dataOut.writeInt(expectedPayload2);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(), sendPingFrame(true, expectedPayload1, expectedPayload2));

    FrameReader fr = newReader(out);

    fr.nextFrame(new BaseTestHandler() { // Consume the ping frame.
      @Override public void ping(boolean ack, int payload1, int payload2) {
        assertTrue(ack);
        assertEquals(expectedPayload1, payload1);
        assertEquals(expectedPayload2, payload2);
      }
    });
  }

  @Test public void maxLengthDataFrame() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final byte[] expectedData = new byte[16383];
    Arrays.fill(expectedData, (byte) 2);

    // Write the data frame.
    dataOut.writeShort(expectedData.length);
    dataOut.write(Http20Draft09.TYPE_DATA);
    dataOut.write(0); // no flags
    dataOut.writeInt(expectedStreamId & 0x7fffffff);
    dataOut.write(expectedData);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(), sendDataFrame(expectedData));

    FrameReader fr = newReader(out);

    fr.nextFrame(new BaseTestHandler() {
      @Override public void data(boolean inFinished, int streamId, InputStream in, int length)
          throws IOException {
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(16383, length);
        byte[] data = new byte[length];
        Util.readFully(in, data);
        for (byte b : data){
          assertEquals(2, b);
        }
      }
    });
  }

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new byte[0x1000000]);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("FRAME_SIZE_ERROR max size is 16383: 16777216", e.getMessage());
    }
  }

  @Test public void windowUpdateRoundTrip() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final long expectedWindowSizeIncrement = 0x7fffffff;

    // Compose the expected window update frame.
    dataOut.writeShort(4); // length
    dataOut.write(Http20Draft09.TYPE_WINDOW_UPDATE);
    dataOut.write(0); // No flags.
    dataOut.writeInt(expectedStreamId);
    dataOut.writeInt((int) expectedWindowSizeIncrement);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(), windowUpdate(expectedWindowSizeIncrement));

    FrameReader fr = newReader(out);

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
      assertEquals("windowSizeIncrement must be between 1 and 0x7fffffff: 0", e.getMessage());
    }
    try {
      windowUpdate(0x80000000L);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("windowSizeIncrement must be between 1 and 0x7fffffff: 2147483648",
          e.getMessage());
    }
  }

  @Test public void goAwayWithoutDebugDataRoundTrip() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    // Compose the expected GOAWAY frame without debug data.
    dataOut.writeShort(8); // Without debug data there's only 2 32-bit fields.
    dataOut.write(Http20Draft09.TYPE_GOAWAY);
    dataOut.write(0); // no flags.
    dataOut.writeInt(0); // connection-scope
    dataOut.writeInt(expectedStreamId); // last good stream.
    dataOut.writeInt(expectedError.httpCode);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(),
        sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY));

    FrameReader fr = newReader(out);

    fr.nextFrame(new BaseTestHandler() { // Consume the go away frame.
      @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData) {
        assertEquals(expectedStreamId, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertEquals(0, debugData.length);
      }
    });
  }

  @Test public void goAwayWithDebugDataRoundTrip() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;
    final byte[] expectedData = new byte[8];
    Arrays.fill(expectedData, (byte) '*');

    // Compose the expected GOAWAY frame without debug data.
    dataOut.writeShort(8 + expectedData.length);
    dataOut.write(Http20Draft09.TYPE_GOAWAY);
    dataOut.write(0); // no flags.
    dataOut.writeInt(0); // connection-scope
    dataOut.writeInt(0); // never read any stream!
    dataOut.writeInt(expectedError.httpCode);
    dataOut.write(expectedData);

    // Check writer sends the same bytes.
    assertArrayEquals(out.toByteArray(), sendGoAway(0, expectedError, expectedData));

    FrameReader fr = newReader(out);

    fr.nextFrame(new BaseTestHandler() { // Consume the go away frame.
      @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData) {
        assertEquals(0, lastGoodStreamId);
        assertEquals(expectedError, errorCode);
        assertArrayEquals(expectedData, debugData);
      }
    });
  }

  private Http20Draft09.Reader newReader(ByteArrayOutputStream out) {
    return new Http20Draft09.Reader(new ByteArrayInputStream(out.toByteArray()),
        Variant.HTTP_20_DRAFT_09.initialPeerSettings(false).getHeaderTableSize(), false);
  }

  private byte[] literalHeaders(List<Header> sentHeaders) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new HpackDraft05.Writer(new DataOutputStream(out)).writeHeaders(sentHeaders);
    return out.toByteArray();
  }

  private byte[] sendPingFrame(boolean ack, int payload1, int payload2) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Http20Draft09.Writer(out, true).ping(ack, payload1, payload2);
    return out.toByteArray();
  }

  private byte[] sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Http20Draft09.Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out.toByteArray();
  }

  private byte[] sendDataFrame(byte[] data) throws IOException {
    return sendDataFrame(data, 0, data.length);
  }

  private byte[] sendDataFrame(byte[] data, int offset, int byteCount) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Http20Draft09.Writer(out, true).sendDataFrame(expectedStreamId, 0, data, offset, byteCount);
    return out.toByteArray();
  }

  private byte[] windowUpdate(long windowSizeIncrement) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Http20Draft09.Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement);
    return out.toByteArray();
  }
}
