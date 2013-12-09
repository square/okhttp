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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Http20Draft06Test {
  static final int expectedStreamId = 15;

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<String> sentHeaders = Arrays.asList("name", "value");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Write the headers frame, specifying no more frames are expected.
    {
      byte[] headerBytes = literalHeaders(sentHeaders);
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft06.TYPE_HEADERS);
      dataOut.write(Http20Draft06.FLAG_END_HEADERS | Http20Draft06.FLAG_END_STREAM);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    FrameReader fr = new Http20Draft06.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Consume the headers frame.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<String> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertTrue(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(sentHeaders, nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void headersFrameThenContinuation() throws IOException {

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Write the first headers frame.
    {
      byte[] headerBytes = literalHeaders(Arrays.asList("foo", "bar"));
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft06.TYPE_HEADERS);
      dataOut.write(0); // no flags
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    // Write the continuation frame, specifying no more frames are expected.
    {
      byte[] headerBytes = literalHeaders(Arrays.asList("baz", "qux"));
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft06.TYPE_CONTINUATION);
      dataOut.write(Http20Draft06.FLAG_END_HEADERS | Http20Draft06.FLAG_END_STREAM);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    FrameReader fr = new Http20Draft06.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Reading the above frames should result in a concatenated nameValueBlock.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<String> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertTrue(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(Arrays.asList("foo", "bar", "baz", "qux"), nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message.
   * Ensure the larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    char[] tooLarge = new char[4096];
    Arrays.fill(tooLarge, 'a');
    final List<String> sentHeaders = Arrays.asList("foo", new String(tooLarge));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    writeOnlyHeadersFrame(literalHeaders(sentHeaders), dataOut);

    FrameReader fr = new Http20Draft06.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Consume the large header set.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<String> nameValueBlock,
          HeadersMode headersMode) {
        assertEquals(sentHeaders, nameValueBlock);
      }
    });
  }

  @Test public void usingDraft06Examples() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    writeOnlyHeadersFrame(firstHeaderSetBytes(), dataOut);
    writeOnlyHeadersFrame(secondHeaderSetBytes(), dataOut);

    FrameReader fr = new Http20Draft06.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Consume the first header set.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<String> nameValueBlock,
          HeadersMode headersMode) {
        assertEquals(Arrays.asList(":path", "/my-example/index.html", "user-agent", "my-user-agent",
            "mynewheader", "first"), nameValueBlock);
      }
    });

    // Consume the second header set.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<String> nameValueBlock,
          HeadersMode headersMode) {
        assertEquals(Arrays.asList(
            ":path", "/my-example/resources/script.js",
            "user-agent", "my-user-agent",
            "mynewheader", "second"
        ), nameValueBlock);
      }
    });
  }

  // Deviates from draft only to fix doc bugs noted in https://github.com/igrigorik/http-2 specs.
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-03#appendix-C.1
  static byte[] firstHeaderSetBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x44); // literal header with incremental indexing, name index = 3
    out.write(0x16); // header value string length = 22
    out.write("/my-example/index.html".getBytes(), 0, 22);

    out.write(0x4C); // literal header with incremental indexing, name index = 11
    out.write(0x0D); // header value string length = 13
    out.write("my-user-agent".getBytes(), 0, 13);

    out.write(0x40); // literal header with incremental indexing, new name
    out.write(0x0B); // header name string length = 11
    out.write("mynewheader".getBytes(), 0, 11);
    out.write(0x05); // header value string length = 5
    out.write("first".getBytes(), 0, 5);

    return out.toByteArray();
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-03#appendix-C.2
  static byte[] secondHeaderSetBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x9e); // indexed header, index = 30: removal from reference set
    out.write(0xa0); // indexed header, index = 32: removal from reference set
    out.write(0x04); // literal header, substitution indexing, name index = 3

    out.write(0x1e); // replaced entry index = 30
    out.write(0x1f); // header value string length = 31
    out.write("/my-example/resources/script.js".getBytes(), 0, 31);

    out.write(0x5f);
    out.write(0x02); // literal header, incremental indexing, name index = 32
    out.write(0x06); // header value string length = 6
    out.write("second".getBytes(), 0, 6);

    return out.toByteArray();
  }

  static void writeOnlyHeadersFrame(byte[] headersSet, DataOutputStream dataOut)
      throws IOException {
    dataOut.writeShort(headersSet.length);
    dataOut.write(Http20Draft06.TYPE_HEADERS);
    dataOut.write(Http20Draft06.FLAG_END_HEADERS | Http20Draft06.FLAG_END_STREAM);
    dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream 15 with reserved bit set
    dataOut.write(headersSet);
  }

  static byte[] literalHeaders(List<String> sentHeaders) throws IOException {
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    new Hpack.Writer(new DataOutputStream(headerBytes)).writeHeaders(sentHeaders);
    return headerBytes.toByteArray();
  }
}
