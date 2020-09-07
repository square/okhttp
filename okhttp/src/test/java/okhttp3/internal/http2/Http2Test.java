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
package okhttp3.internal.http2;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.GzipSink;
import okio.Okio;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.TestUtil.headerEntries;
import static okhttp3.internal.http2.Http2.FLAG_COMPRESSED;
import static okhttp3.internal.http2.Http2.FLAG_END_HEADERS;
import static okhttp3.internal.http2.Http2.FLAG_END_STREAM;
import static okhttp3.internal.http2.Http2.FLAG_NONE;
import static okhttp3.internal.http2.Http2.FLAG_PADDED;
import static okhttp3.internal.http2.Http2.FLAG_PRIORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class Http2Test {
  final Buffer frame = new Buffer();
  final Http2Reader reader = new Http2Reader(frame, false);
  final int expectedStreamId = 15;

  @Test public void unknownFrameTypeSkipped() throws IOException {
    writeMedium(frame, 4); // has a 4-byte field
    frame.writeByte(99); // type 99
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId);
    frame.writeInt(111111111); // custom data

    reader.nextFrame(false, new BaseTestHandler()); // Should not callback.
  }

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    Buffer headerBytes = literalHeaders(sentHeaders);
    writeMedium(frame, (int) headerBytes.size());
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_END_STREAM);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBytes);

    // Check writer sends the same bytes.
    assertThat(sendHeaderFrames(true, sentHeaders)).isEqualTo(frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void headers(boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock) {
        assertThat(inFinished).isTrue();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(associatedStreamId).isEqualTo(-1);
        assertThat(headerBlock).isEqualTo(sentHeaders);
      }
    });
  }

  @Test public void headersWithPriority() throws IOException {
    final List<Header> sentHeaders = headerEntries("name", "value");

    Buffer headerBytes = literalHeaders(sentHeaders);
    writeMedium(frame, (int) (headerBytes.size() + 5));
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PRIORITY);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(0); // Independent stream.
    frame.writeByte(255); // Heaviest weight, zero-indexed.
    frame.writeAll(headerBytes);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void priority(int streamId, int streamDependency, int weight,
          boolean exclusive) {
        assertThat(streamDependency).isEqualTo(0);
        assertThat(weight).isEqualTo(256);
        assertThat(exclusive).isFalse();
      }

      @Override public void headers(boolean inFinished, int streamId,
          int associatedStreamId, List<Header> nameValueBlock) {
        assertThat(inFinished).isFalse();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(associatedStreamId).isEqualTo(-1);
        assertThat(nameValueBlock).isEqualTo(sentHeaders);
      }
    });
  }

  /** Headers are compressed, then framed. */
  @Test public void headersFrameThenContinuation() throws IOException {
    final List<Header> sentHeaders = largeHeaders();

    Buffer headerBlock = literalHeaders(sentHeaders);

    // Write the first headers frame.
    writeMedium(frame, Http2.INITIAL_MAX_FRAME_SIZE);
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(headerBlock, Http2.INITIAL_MAX_FRAME_SIZE);

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, (int) headerBlock.size());
    frame.writeByte(Http2.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBlock);

    // Check writer sends the same bytes.
    assertThat(sendHeaderFrames(false, sentHeaders)).isEqualTo(frame);

    // Reading the above frames should result in a concatenated headerBlock.
    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void headers(boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock) {
        assertThat(inFinished).isFalse();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(associatedStreamId).isEqualTo(-1);
        assertThat(headerBlock).isEqualTo(sentHeaders);
      }
    });
  }

  @Test public void pushPromise() throws IOException {
    final int expectedPromisedStreamId = 11;

    final List<Header> pushPromise = asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/")
    );

    // Write the push promise frame, specifying the associated stream ID.
    Buffer headerBytes = literalHeaders(pushPromise);
    writeMedium(frame, (int) (headerBytes.size() + 4));
    frame.writeByte(Http2.TYPE_PUSH_PROMISE);
    frame.writeByte(Http2.FLAG_END_PUSH_PROMISE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
    frame.writeAll(headerBytes);

    assertThat(sendPushPromiseFrames(expectedPromisedStreamId, pushPromise)).isEqualTo(
        frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override
      public void pushPromise(int streamId, int promisedStreamId, List<Header> headerBlock) {
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(promisedStreamId).isEqualTo(expectedPromisedStreamId);
        assertThat(headerBlock).isEqualTo(pushPromise);
      }
    });
  }

  /** Headers are compressed, then framed. */
  @Test public void pushPromiseThenContinuation() throws IOException {
    final int expectedPromisedStreamId = 11;
    final List<Header> pushPromise = largeHeaders();

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(pushPromise);

    // Write the first headers frame.
    writeMedium(frame, Http2.INITIAL_MAX_FRAME_SIZE);
    frame.writeByte(Http2.TYPE_PUSH_PROMISE);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(expectedPromisedStreamId & 0x7fffffff);
    frame.write(headerBlock, Http2.INITIAL_MAX_FRAME_SIZE - 4);

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, (int) headerBlock.size());
    frame.writeByte(Http2.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBlock);

    assertThat(sendPushPromiseFrames(expectedPromisedStreamId, pushPromise)).isEqualTo(
        frame);

    // Reading the above frames should result in a concatenated headerBlock.
    reader.nextFrame(false, new BaseTestHandler() {
      @Override
      public void pushPromise(int streamId, int promisedStreamId, List<Header> headerBlock) {
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(promisedStreamId).isEqualTo(expectedPromisedStreamId);
        assertThat(headerBlock).isEqualTo(pushPromise);
      }
    });
  }

  @Test public void readRstStreamFrame() throws IOException {
    writeMedium(frame, 4);
    frame.writeByte(Http2.TYPE_RST_STREAM);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeInt(ErrorCode.PROTOCOL_ERROR.getHttpCode());

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR);
      }
    });
  }

  @Test public void readSettingsFrame() throws IOException {
    final int reducedTableSizeBytes = 16;

    writeMedium(frame, 12); // 2 settings * 6 bytes (2 for the code and 4 for the value).
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(1); // SETTINGS_HEADER_TABLE_SIZE
    frame.writeInt(reducedTableSizeBytes);
    frame.writeShort(2); // SETTINGS_ENABLE_PUSH
    frame.writeInt(0);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        // No clearPrevious in HTTP/2.
        assertThat(clearPrevious).isFalse();
        assertThat(settings.getHeaderTableSize()).isEqualTo(reducedTableSizeBytes);
        assertThat(settings.getEnablePush(true)).isFalse();
      }
    });
  }

  @Test public void readSettingsFrameInvalidPushValue() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(2);
    frame.writeInt(2);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1");
    }
  }

  @Test public void readSettingsFrameUnknownSettingId() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(7); // old number for SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(1);

    final AtomicInteger settingValue = new AtomicInteger();
    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        settingValue.set(settings.get(7));
      }
    });
    assertThat(1).isEqualTo(settingValue.intValue());
  }

  @Test public void readSettingsFrameExperimentalId() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.write(ByteString.decodeHex("f000")); // Id reserved for experimental use.
    frame.writeInt(1);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void settings(boolean clearPrevious, Settings settings) {
        // no-op
      }
    });
  }

  @Test public void readSettingsFrameNegativeWindowSize() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(4); // SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(Integer.MIN_VALUE);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1");
    }
  }

  @Test public void readSettingsFrameNegativeFrameLength() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(5); // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt(Integer.MIN_VALUE);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: -2147483648");
    }
  }

  @Test public void readSettingsFrameTooShortFrameLength() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(5); // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt((int) Math.pow(2, 14) - 1);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: 16383");
    }
  }

  @Test public void readSettingsFrameTooLongFrameLength() throws IOException {
    writeMedium(frame, 6); // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // Settings are always on the connection stream 0.
    frame.writeShort(5); // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt((int) Math.pow(2, 24));

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: 16777216");
    }
  }

  @Test public void pingRoundTrip() throws IOException {
    final int expectedPayload1 = 7;
    final int expectedPayload2 = 8;

    writeMedium(frame, 8); // length
    frame.writeByte(Http2.TYPE_PING);
    frame.writeByte(Http2.FLAG_ACK);
    frame.writeInt(0); // connection-level
    frame.writeInt(expectedPayload1);
    frame.writeInt(expectedPayload2);

    // Check writer sends the same bytes.
    assertThat(sendPingFrame(true, expectedPayload1, expectedPayload2)).isEqualTo(frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void ping(boolean ack, int payload1, int payload2) {
        assertThat(ack).isTrue();
        assertThat(payload1).isEqualTo(expectedPayload1);
        assertThat(payload2).isEqualTo(expectedPayload2);
      }
    });
  }

  @Test public void maxLengthDataFrame() throws IOException {
    final byte[] expectedData = new byte[Http2.INITIAL_MAX_FRAME_SIZE];
    Arrays.fill(expectedData, (byte) 2);

    writeMedium(frame, expectedData.length);
    frame.writeByte(Http2.TYPE_DATA);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.write(expectedData);

    // Check writer sends the same bytes.
    assertThat(sendDataFrame(new Buffer().write(expectedData))).isEqualTo(frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void data(boolean inFinished, int streamId, BufferedSource source,
          int length) throws IOException {
        assertThat(inFinished).isFalse();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(length).isEqualTo(Http2.INITIAL_MAX_FRAME_SIZE);
        ByteString data = source.readByteString(length);
        for (byte b : data.toByteArray()) {
          assertThat(b).isEqualTo((byte) 2);
        }
      }
    });
  }

  @Test public void dataFrameNotAssociateWithStream() throws IOException {
    byte[] payload = new byte[] {0x01, 0x02};

    writeMedium(frame, payload.length);
    frame.writeByte(Http2.TYPE_DATA);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0);
    frame.write(payload);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("PROTOCOL_ERROR: TYPE_DATA streamId == 0");
    }
  }

  /** We do not send SETTINGS_COMPRESS_DATA = 1, nor want to. Let's make sure we error. */
  @Test public void compressedDataFrameWhenSettingDisabled() throws IOException {
    byte[] expectedData = new byte[Http2.INITIAL_MAX_FRAME_SIZE];
    Arrays.fill(expectedData, (byte) 2);
    Buffer zipped = gzip(expectedData);
    int zippedSize = (int) zipped.size();

    writeMedium(frame, zippedSize);
    frame.writeByte(Http2.TYPE_DATA);
    frame.writeByte(FLAG_COMPRESSED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    zipped.readAll(frame);

    try {
      reader.nextFrame(false, new BaseTestHandler());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA");
    }
  }

  @Test public void readPaddedDataFrame() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    int paddingLength = 254;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    writeMedium(frame, dataLength + paddingLength + 1);
    frame.writeByte(Http2.TYPE_DATA);
    frame.writeByte(FLAG_PADDED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(paddingLength);
    frame.write(expectedData);
    frame.write(padding);

    reader.nextFrame(false, assertData());
    // Padding was skipped.
    assertThat(frame.exhausted()).isTrue();
  }

  @Test public void readPaddedDataFrameZeroPadding() throws IOException {
    int dataLength = 1123;
    byte[] expectedData = new byte[dataLength];
    Arrays.fill(expectedData, (byte) 2);

    writeMedium(frame, dataLength + 1);
    frame.writeByte(Http2.TYPE_DATA);
    frame.writeByte(FLAG_PADDED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.write(expectedData);

    reader.nextFrame(false, assertData());
  }

  @Test public void readPaddedHeadersFrame() throws IOException {
    int paddingLength = 254;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    writeMedium(frame, (int) headerBlock.size() + paddingLength + 1);
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PADDED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(paddingLength);
    frame.writeAll(headerBlock);
    frame.write(padding);

    reader.nextFrame(false, assertHeaderBlock());
    // Padding was skipped.
    assertThat(frame.exhausted()).isTrue();
  }

  @Test public void readPaddedHeadersFrameZeroPadding() throws IOException {
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));
    writeMedium(frame, (int) headerBlock.size() + 1);
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(FLAG_END_HEADERS | FLAG_PADDED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(0);
    frame.writeAll(headerBlock);

    reader.nextFrame(false, assertHeaderBlock());
  }

  /** Headers are compressed, then framed. */
  @Test public void readPaddedHeadersFrameThenContinuation() throws IOException {
    int paddingLength = 254;
    byte[] padding = new byte[paddingLength];
    Arrays.fill(padding, (byte) 0);

    // Decoding the first header will cross frame boundaries.
    Buffer headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"));

    // Write the first headers frame.
    writeMedium(frame, (int) (headerBlock.size() / 2) + paddingLength + 1);
    frame.writeByte(Http2.TYPE_HEADERS);
    frame.writeByte(FLAG_PADDED);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeByte(paddingLength);
    frame.write(headerBlock, headerBlock.size() / 2);
    frame.write(padding);

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, (int) headerBlock.size());
    frame.writeByte(Http2.TYPE_CONTINUATION);
    frame.writeByte(FLAG_END_HEADERS);
    frame.writeInt(expectedStreamId & 0x7fffffff);
    frame.writeAll(headerBlock);

    reader.nextFrame(false, assertHeaderBlock());
    assertThat(frame.exhausted()).isTrue();
  }

  @Test public void tooLargeDataFrame() throws IOException {
    try {
      sendDataFrame(new Buffer().write(new byte[0x1000000]));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo("FRAME_SIZE_ERROR length > 16384: 16777216");
    }
  }

  @Test public void windowUpdateRoundTrip() throws IOException {
    final long expectedWindowSizeIncrement = 0x7fffffff;

    writeMedium(frame, 4); // length
    frame.writeByte(Http2.TYPE_WINDOW_UPDATE);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(expectedStreamId);
    frame.writeInt((int) expectedWindowSizeIncrement);

    // Check writer sends the same bytes.
    assertThat(windowUpdate(expectedWindowSizeIncrement)).isEqualTo(frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(windowSizeIncrement).isEqualTo(expectedWindowSizeIncrement);
      }
    });
  }

  @Test public void badWindowSizeIncrement() throws IOException {
    try {
      windowUpdate(0);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo(
          "windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 0");
    }
    try {
      windowUpdate(0x80000000L);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo(
          "windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 2147483648");
    }
  }

  @Test public void goAwayWithoutDebugDataRoundTrip() throws IOException {
    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;

    writeMedium(frame, 8); // Without debug data there's only 2 32-bit fields.
    frame.writeByte(Http2.TYPE_GOAWAY);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // connection-scope
    frame.writeInt(expectedStreamId); // last good stream.
    frame.writeInt(expectedError.getHttpCode());

    // Check writer sends the same bytes.
    assertThat(sendGoAway(expectedStreamId, expectedError, Util.EMPTY_BYTE_ARRAY)).isEqualTo(
        frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertThat(lastGoodStreamId).isEqualTo(expectedStreamId);
        assertThat(errorCode).isEqualTo(expectedError);
        assertThat(debugData.size()).isEqualTo(0);
      }
    });
  }

  @Test public void goAwayWithDebugDataRoundTrip() throws IOException {
    final ErrorCode expectedError = ErrorCode.PROTOCOL_ERROR;
    final ByteString expectedData = ByteString.encodeUtf8("abcdefgh");

    // Compose the expected GOAWAY frame without debug data.
    writeMedium(frame, 8 + expectedData.size());
    frame.writeByte(Http2.TYPE_GOAWAY);
    frame.writeByte(Http2.FLAG_NONE);
    frame.writeInt(0); // connection-scope
    frame.writeInt(0); // never read any stream!
    frame.writeInt(expectedError.getHttpCode());
    frame.write(expectedData.toByteArray());

    // Check writer sends the same bytes.
    assertThat(sendGoAway(0, expectedError, expectedData.toByteArray())).isEqualTo(frame);

    reader.nextFrame(false, new BaseTestHandler() {
      @Override public void goAway(
          int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
        assertThat(lastGoodStreamId).isEqualTo(0);
        assertThat(errorCode).isEqualTo(expectedError);
        assertThat(debugData).isEqualTo(expectedData);
      }
    });
  }

  @Test public void frameSizeError() throws IOException {
    Http2Writer writer = new Http2Writer(new Buffer(), true);

    try {
      writer.frameHeader(0, 16777216, Http2.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      // TODO: real max is based on settings between 16384 and 16777215
      assertThat(e.getMessage()).isEqualTo("FRAME_SIZE_ERROR length > 16384: 16777216");
    }
  }

  @Test public void ackSettingsAppliesMaxFrameSize() throws IOException {
    int newMaxFrameSize = 16777215;

    Http2Writer writer = new Http2Writer(new Buffer(), true);

    writer.applyAndAckSettings(new Settings().set(Settings.MAX_FRAME_SIZE, newMaxFrameSize));

    assertThat(writer.maxDataLength()).isEqualTo(newMaxFrameSize);
    writer.frameHeader(0, newMaxFrameSize, Http2.TYPE_DATA, FLAG_NONE);
  }

  @Test public void streamIdHasReservedBit() throws IOException {
    Http2Writer writer = new Http2Writer(new Buffer(), true);

    try {
      int streamId = 3;
      streamId |= 1L << 31; // set reserved bit
      writer.frameHeader(streamId, Http2.INITIAL_MAX_FRAME_SIZE, Http2.TYPE_DATA, FLAG_NONE);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo("reserved bit set: -2147483645");
    }
  }

  private Buffer literalHeaders(List<Header> sentHeaders) throws IOException {
    Buffer out = new Buffer();
    new Hpack.Writer(out).writeHeaders(sentHeaders);
    return out;
  }

  private Buffer sendHeaderFrames(boolean outFinished, List<Header> headers) throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).headers(outFinished, expectedStreamId, headers);
    return out;
  }

  private Buffer sendPushPromiseFrames(int streamId, List<Header> headers) throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).pushPromise(expectedStreamId, streamId, headers);
    return out;
  }

  private Buffer sendPingFrame(boolean ack, int payload1, int payload2) throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).ping(ack, payload1, payload2);
    return out;
  }

  private Buffer sendGoAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData);
    return out;
  }

  private Buffer sendDataFrame(Buffer data) throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).dataFrame(expectedStreamId, FLAG_NONE, data,
        (int) data.size());
    return out;
  }

  private Buffer windowUpdate(long windowSizeIncrement) throws IOException {
    Buffer out = new Buffer();
    new Http2Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement);
    return out;
  }

  private Http2Reader.Handler assertHeaderBlock() {
    return new BaseTestHandler() {
      @Override public void headers(boolean inFinished, int streamId,
          int associatedStreamId, List<Header> headerBlock) {
        assertThat(inFinished).isFalse();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(associatedStreamId).isEqualTo(-1);
        assertThat(headerBlock).isEqualTo(headerEntries("foo", "barrr", "baz", "qux"));
      }
    };
  }

  private Http2Reader.Handler assertData() {
    return new BaseTestHandler() {
      @Override public void data(boolean inFinished, int streamId, BufferedSource source,
          int length) throws IOException {
        assertThat(inFinished).isFalse();
        assertThat(streamId).isEqualTo(expectedStreamId);
        assertThat(length).isEqualTo(1123);
        ByteString data = source.readByteString(length);
        for (byte b : data.toByteArray()) {
          assertThat(b).isEqualTo((byte) 2);
        }
      }
    };
  }

  private static Buffer gzip(byte[] data) throws IOException {
    Buffer buffer = new Buffer();
    Okio.buffer(new GzipSink(buffer)).write(data).close();
    return buffer;
  }

  /** Create a sufficiently large header set to overflow INITIAL_MAX_FRAME_SIZE bytes. */
  private static List<Header> largeHeaders() {
    String[] nameValues = new String[32];
    char[] chars = new char[512];
    for (int i = 0; i < nameValues.length; ) {
      Arrays.fill(chars, (char) i);
      nameValues[i++] = nameValues[i++] = String.valueOf(chars);
    }
    return headerEntries(nameValues);
  }

  private static void writeMedium(BufferedSink sink, int i) throws IOException {
    sink.writeByte((i >>> 16) & 0xff);
    sink.writeByte((i >>> 8) & 0xff);
    sink.writeByte(i & 0xff);
  }
}
