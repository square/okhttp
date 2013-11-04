/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.squareup.okhttp.internal.Base64;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static com.squareup.okhttp.internal.spdy.ErrorCode.CANCEL;
import static com.squareup.okhttp.internal.spdy.ErrorCode.FLOW_CONTROL_ERROR;
import static com.squareup.okhttp.internal.spdy.ErrorCode.INTERNAL_ERROR;
import static com.squareup.okhttp.internal.spdy.ErrorCode.INVALID_STREAM;
import static com.squareup.okhttp.internal.spdy.ErrorCode.PROTOCOL_ERROR;
import static com.squareup.okhttp.internal.spdy.ErrorCode.REFUSED_STREAM;
import static com.squareup.okhttp.internal.spdy.ErrorCode.STREAM_IN_USE;
import static com.squareup.okhttp.internal.spdy.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_DATA;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_GOAWAY;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_HEADERS;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_NOOP;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_PING;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_WINDOW_UPDATE;
import static com.squareup.okhttp.internal.spdy.SpdyStream.WINDOW_UPDATE_THRESHOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SpdyConnectionTest {
  private static final IncomingStreamHandler REJECT_INCOMING_STREAMS = new IncomingStreamHandler() {
    @Override public void receive(SpdyStream stream) throws IOException {
      throw new AssertionError();
    }
  };
  private final MockSpdyPeer peer = new MockSpdyPeer(false);

  @After public void tearDown() throws Exception {
    peer.close();
  }

  @Test public void clientCreatesStreamAndServerReplies() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
    assertStreamData("robot", stream.getInputStream());
    writeAndClose(stream, "c3po");
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertFalse(synStream.inFinished);
    assertFalse(synStream.outFinished);
    assertEquals(1, synStream.streamId);
    assertEquals(0, synStream.associatedStreamId);
    assertEquals(Arrays.asList("b", "banana"), synStream.nameValueBlock);
    MockSpdyPeer.InFrame requestData = peer.takeFrame();
    assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
  }

  @Test public void headersOnlyStreamIsClosedAfterReplyHeaders() throws Exception {
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("b", "banana"));
    peer.play();

    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), false, false);
    assertEquals(1, connection.openStreamCount());
    assertEquals(Arrays.asList("b", "banana"), stream.getResponseHeaders());
    assertEquals(0, connection.openStreamCount());
  }

  @Test public void clientCreatesStreamAndServerRepliesWithFin() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(true, 1, Arrays.asList("a", "android"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    connection.newStream(Arrays.asList("b", "banana"), false, true);
    assertEquals(1, connection.openStreamCount());
    connection.ping().roundTripTime(); // Ensure that the SYN_REPLY has been received.
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
  }

  @Test public void serverCreatesStreamAndClientReplies() throws Exception {
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, 5, 129, Arrays.asList("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        receiveCount.incrementAndGet();
        assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
        assertEquals(null, stream.getErrorCode());
        assertEquals(5, stream.getPriority());
        stream.reply(Arrays.asList("b", "banana"), true);
      }
    };
    new SpdyConnection.Builder(true, peer.openSocket()).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
    assertFalse(reply.inFinished);
    assertEquals(2, reply.streamId);
    assertEquals(Arrays.asList("b", "banana"), reply.nameValueBlock);
    assertEquals(1, receiveCount.get());
  }

  @Test public void replyWithNoData() throws Exception {
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, Arrays.asList("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        stream.reply(Arrays.asList("b", "banana"), false);
        receiveCount.incrementAndGet();
      }
    };
    new SpdyConnection.Builder(true, peer.openSocket()).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
    assertTrue(reply.inFinished);
    assertEquals(Arrays.asList("b", "banana"), reply.nameValueBlock);
    assertEquals(1, receiveCount.get());
  }

  @Test public void noop() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // NOOP
    peer.play();

    // play it back
    SpdyConnection connection =
        new SpdyConnection.Builder(true, peer.openSocket()).handler(REJECT_INCOMING_STREAMS)
            .build();
    connection.noop();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_NOOP, ping.type);
  }

  @Test public void serverPingsClient() throws Exception {
    // write the mocking script
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    new SpdyConnection.Builder(true, peer.openSocket()).handler(REJECT_INCOMING_STREAMS).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(2, ping.streamId);
  }

  @Test public void clientPingsServer() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    Ping ping = connection.ping();
    assertTrue(ping.roundTripTime() > 0);
    assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
    assertEquals(TYPE_PING, pingFrame.type);
    assertEquals(1, pingFrame.streamId);
  }

  @Test public void unexpectedPingIsNotReturned() throws Exception {
    // write the mocking script
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 3, 0); // This ping will not be returned.
    peer.sendFrame().ping(false, 4, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    new SpdyConnection.Builder(true, peer.openSocket()).handler(REJECT_INCOMING_STREAMS).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping2 = peer.takeFrame();
    assertEquals(2, ping2.streamId);
    MockSpdyPeer.InFrame ping4 = peer.takeFrame();
    assertEquals(4, ping4.streamId);
  }

  @Test public void serverSendsSettingsToClient() throws Exception {
    // write the mocking script
    Settings settings = new Settings();
    settings.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 10);
    peer.sendFrame().settings(settings);
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();

    peer.takeFrame(); // Guarantees that the Settings frame has been processed.
    synchronized (connection) {
      assertEquals(10, connection.settings.getMaxConcurrentStreams(-1));
    }
  }

  @Test public void multipleSettingsFramesAreMerged() throws Exception {
    // write the mocking script
    Settings settings1 = new Settings();
    settings1.set(Settings.UPLOAD_BANDWIDTH, PERSIST_VALUE, 100);
    settings1.set(Settings.DOWNLOAD_BANDWIDTH, PERSIST_VALUE, 200);
    settings1.set(Settings.DOWNLOAD_RETRANS_RATE, 0, 300);
    peer.sendFrame().settings(settings1);
    Settings settings2 = new Settings();
    settings2.set(Settings.DOWNLOAD_BANDWIDTH, 0, 400);
    settings2.set(Settings.DOWNLOAD_RETRANS_RATE, PERSIST_VALUE, 500);
    settings2.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 600);
    peer.sendFrame().settings(settings2);
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame();
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();

    peer.takeFrame(); // Guarantees that the Settings frame has been processed.
    synchronized (connection) {
      assertEquals(100, connection.settings.getUploadBandwidth(-1));
      assertEquals(PERSIST_VALUE, connection.settings.flags(Settings.UPLOAD_BANDWIDTH));
      assertEquals(400, connection.settings.getDownloadBandwidth(-1));
      assertEquals(0, connection.settings.flags(Settings.DOWNLOAD_BANDWIDTH));
      assertEquals(500, connection.settings.getDownloadRetransRate(-1));
      assertEquals(PERSIST_VALUE, connection.settings.flags(Settings.DOWNLOAD_RETRANS_RATE));
      assertEquals(600, connection.settings.getMaxConcurrentStreams(-1));
      assertEquals(PERSIST_VALUE, connection.settings.flags(Settings.MAX_CONCURRENT_STREAMS));
    }
  }

  @Test public void bogusDataFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().data(true, 42, "bogus".getBytes("UTF-8"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    new SpdyConnection.Builder(true, peer.openSocket()).handler(REJECT_INCOMING_STREAMS).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(42, rstStream.streamId);
    assertEquals(INVALID_STREAM, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(2, ping.streamId);
  }

  @Test public void bogusReplyFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().synReply(false, 42, Arrays.asList("a", "android"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    new SpdyConnection.Builder(true, peer.openSocket()).handler(REJECT_INCOMING_STREAMS).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(42, rstStream.streamId);
    assertEquals(INVALID_STREAM, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(2, ping.streamId);
  }

  @Test public void clientClosesClientOutputStream() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("b", "banana"));
    peer.acceptFrame(); // TYPE_DATA
    peer.acceptFrame(); // TYPE_DATA with FLAG_FIN
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, false);
    OutputStream out = stream.getOutputStream();
    out.write("square".getBytes(UTF_8));
    out.flush();
    assertEquals(1, connection.openStreamCount());
    out.close();
    try {
      out.write("round".getBytes(UTF_8));
      fail();
    } catch (Exception expected) {
      assertEquals("stream closed", expected.getMessage());
    }
    connection.ping().roundTripTime(); // Ensure that the SYN_REPLY has been received.
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertFalse(synStream.inFinished);
    assertTrue(synStream.outFinished);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(TYPE_DATA, data.type);
    assertFalse(data.inFinished);
    assertTrue(Arrays.equals("square".getBytes("UTF-8"), data.data));
    MockSpdyPeer.InFrame fin = peer.takeFrame();
    assertEquals(TYPE_DATA, fin.type);
    assertTrue(fin.inFinished);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(1, ping.streamId);
  }

  @Test public void serverClosesClientOutputStream() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().rstStream(1, CANCEL);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
    OutputStream out = stream.getOutputStream();
    connection.ping().roundTripTime(); // Ensure that the RST_CANCEL has been received.
    try {
      out.write("square".getBytes(UTF_8));
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }
    out.close();
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertFalse(synStream.inFinished);
    assertFalse(synStream.outFinished);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(1, ping.streamId);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(TYPE_DATA, data.type);
    assertEquals(1, data.streamId);
    assertTrue(data.inFinished);
    assertFalse(data.outFinished);
  }

  /**
   * Test that the client sends a RST_STREAM if doing so won't disrupt the
   * output stream.
   */
  @Test public void clientClosesClientInputStream() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), false, true);
    InputStream in = stream.getInputStream();
    OutputStream out = stream.getOutputStream();
    in.close();
    try {
      in.read();
      fail();
    } catch (IOException expected) {
      assertEquals("stream closed", expected.getMessage());
    }
    try {
      out.write('a');
      fail();
    } catch (IOException expected) {
      assertEquals("stream finished", expected.getMessage());
    }
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertTrue(synStream.inFinished);
    assertFalse(synStream.outFinished);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(CANCEL, rstStream.errorCode);
  }

  /**
   * Test that the client doesn't send a RST_STREAM if doing so will disrupt
   * the output stream.
   */
  @Test public void clientClosesClientInputStreamIfOutputStreamIsClosed() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // DATA with FLAG_FIN
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
    InputStream in = stream.getInputStream();
    OutputStream out = stream.getOutputStream();
    in.close();
    try {
      in.read();
      fail();
    } catch (IOException expected) {
      assertEquals("stream closed", expected.getMessage());
    }
    out.write("square".getBytes(UTF_8));
    out.flush();
    out.close();
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertFalse(synStream.inFinished);
    assertFalse(synStream.outFinished);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(TYPE_DATA, data.type);
    assertTrue(Arrays.equals("square".getBytes("UTF-8"), data.data));
    MockSpdyPeer.InFrame fin = peer.takeFrame();
    assertEquals(TYPE_DATA, fin.type);
    assertTrue(fin.inFinished);
    assertFalse(fin.outFinished);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(CANCEL, rstStream.errorCode);
  }

  @Test public void serverClosesClientInputStream() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("b", "banana"));
    peer.sendFrame().data(true, 1, "square".getBytes(UTF_8));
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
        .handler(REJECT_INCOMING_STREAMS)
        .build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), false, true);
    InputStream in = stream.getInputStream();
    assertStreamData("square", in);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    assertTrue(synStream.inFinished);
    assertFalse(synStream.outFinished);
  }

  @Test public void remoteDoubleSynReply() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(false, 1, Arrays.asList("b", "banana"));
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("c", "cola"), true, true);
    assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
    connection.ping().roundTripTime(); // Ensure that the 2nd SYN REPLY has been received.
    try {
      stream.getInputStream().read();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: STREAM_IN_USE", expected.getMessage());
    }

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(1, rstStream.streamId);
    assertEquals(STREAM_IN_USE, rstStream.errorCode);
  }

  @Test public void remoteDoubleSynStream() throws Exception {
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, Arrays.asList("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, Arrays.asList("b", "banana"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        receiveCount.incrementAndGet();
        assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
        assertEquals(null, stream.getErrorCode());
        stream.reply(Arrays.asList("c", "cola"), true);
      }
    };
    new SpdyConnection.Builder(true, peer.openSocket()).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(2, rstStream.streamId);
    assertEquals(PROTOCOL_ERROR, rstStream.errorCode);
    assertEquals(1, receiveCount.intValue());
  }

  @Test public void remoteSendsDataAfterInFinished() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.sendFrame().data(true, 1, "c3po".getBytes("UTF-8")); // Ignored.
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
    assertStreamData("robot", stream.getInputStream());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(2, ping.streamId);
  }

  @Test public void remoteSendsTooMuchData() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("b", "banana"));
    peer.sendFrame().data(false, 1, new byte[64 * 1024 + 1]);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
    assertEquals(Arrays.asList("b", "banana"), stream.getResponseHeaders());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(1, rstStream.streamId);
    assertEquals(FLOW_CONTROL_ERROR, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(2, ping.streamId);
  }

  @Test public void remoteSendsRefusedStreamBeforeReplyHeaders() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().rstStream(1, REFUSED_STREAM);
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
    try {
      stream.getResponseHeaders();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: REFUSED_STREAM", expected.getMessage());
    }
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(2, ping.streamId);
  }

  @Test public void receiveGoAway() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // SYN_STREAM 3
    peer.sendFrame().goAway(1, PROTOCOL_ERROR);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 1
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream1 = connection.newStream(Arrays.asList("a", "android"), true, true);
    SpdyStream stream2 = connection.newStream(Arrays.asList("b", "banana"), true, true);
    connection.ping().roundTripTime(); // Ensure that the GO_AWAY has been received.
    stream1.getOutputStream().write("abc".getBytes(UTF_8));
    try {
      stream2.getOutputStream().write("abc".getBytes(UTF_8));
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: REFUSED_STREAM", expected.getMessage());
    }
    stream1.getOutputStream().write("def".getBytes(UTF_8));
    stream1.getOutputStream().close();
    try {
      connection.newStream(Arrays.asList("c", "cola"), true, true);
      fail();
    } catch (IOException expected) {
      assertEquals("shutdown", expected.getMessage());
    }
    assertEquals(1, connection.openStreamCount());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream1 = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream1.type);
    MockSpdyPeer.InFrame synStream2 = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream2.type);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    MockSpdyPeer.InFrame data1 = peer.takeFrame();
    assertEquals(TYPE_DATA, data1.type);
    assertEquals(1, data1.streamId);
    assertTrue(Arrays.equals("abcdef".getBytes("UTF-8"), data1.data));
  }

  @Test public void sendGoAway() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // GOAWAY
    peer.acceptFrame(); // PING
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, Arrays.asList("b", "b")); // Should be ignored!
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    connection.newStream(Arrays.asList("a", "android"), true, true);
    Ping ping = connection.ping();
    connection.shutdown(PROTOCOL_ERROR);
    assertEquals(1, connection.openStreamCount());
    ping.roundTripTime(); // Prevent the peer from exiting prematurely.

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream1 = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream1.type);
    MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
    assertEquals(TYPE_PING, pingFrame.type);
    MockSpdyPeer.InFrame goaway = peer.takeFrame();
    assertEquals(TYPE_GOAWAY, goaway.type);
    assertEquals(0, goaway.streamId);
    assertEquals(PROTOCOL_ERROR, goaway.errorCode);
  }

  @Test public void noPingsAfterShutdown() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // GOAWAY
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    connection.shutdown(INTERNAL_ERROR);
    try {
      connection.ping();
      fail();
    } catch (IOException expected) {
      assertEquals("shutdown", expected.getMessage());
    }

    // verify the peer received what was expected
    MockSpdyPeer.InFrame goaway = peer.takeFrame();
    assertEquals(TYPE_GOAWAY, goaway.type);
    assertEquals(INTERNAL_ERROR, goaway.errorCode);
  }

  @Test public void close() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // GOAWAY
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
    assertEquals(1, connection.openStreamCount());
    connection.close();
    assertEquals(0, connection.openStreamCount());
    try {
      connection.newStream(Arrays.asList("b", "banana"), true, true);
      fail();
    } catch (IOException expected) {
      assertEquals("shutdown", expected.getMessage());
    }
    try {
      stream.getOutputStream().write(0);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }
    try {
      stream.getInputStream().read();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame goaway = peer.takeFrame();
    assertEquals(TYPE_GOAWAY, goaway.type);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(1, rstStream.streamId);
  }

  @Test public void closeCancelsPings() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // PING
    peer.acceptFrame(); // GOAWAY
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    Ping ping = connection.ping();
    connection.close();
    assertEquals(-1, ping.roundTripTime());
  }

  @Test public void readTimeoutExpires() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    stream.setReadTimeout(1000);
    InputStream in = stream.getInputStream();
    long startNanos = System.nanoTime();
    try {
      in.read();
      fail();
    } catch (IOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertEquals(1000d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(1, connection.openStreamCount());
    connection.ping().roundTripTime(); // Prevent the peer from exiting prematurely.

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
  }

  @Test public void headers() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.sendFrame().headers(1, Arrays.asList("c", "c3po"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
    assertEquals(Arrays.asList("a", "android", "c", "c3po"), stream.getResponseHeaders());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
  }

  @Test public void headersBeforeReply() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(1, Arrays.asList("c", "c3po"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
    try {
      stream.getResponseHeaders();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(PROTOCOL_ERROR, rstStream.errorCode);
  }

  @Test public void readSendsWindowUpdate() throws Exception {
    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    for (int i = 0; i < 3; i++) {
      peer.sendFrame().data(false, 1, new byte[WINDOW_UPDATE_THRESHOLD]);
      peer.acceptFrame(); // WINDOW UPDATE
    }
    peer.sendFrame().data(true, 1, new byte[0]);
    peer.play();

    // Play it back.
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
    InputStream in = stream.getInputStream();
    int total = 0;
    byte[] buffer = new byte[1024];
    int count;
    while ((count = in.read(buffer)) != -1) {
      total += count;
      if (total == 3 * WINDOW_UPDATE_THRESHOLD) break;
    }
    assertEquals(-1, in.read());

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      MockSpdyPeer.InFrame windowUpdate = peer.takeFrame();
      assertEquals(TYPE_WINDOW_UPDATE, windowUpdate.type);
      assertEquals(1, windowUpdate.streamId);
      assertEquals(WINDOW_UPDATE_THRESHOLD, windowUpdate.deltaWindowSize);
    }
  }

  @Test public void writeAwaitsWindowUpdate() throws Exception {
    // Write the mocking script. This accepts more data frames than necessary!
    peer.acceptFrame(); // SYN_STREAM
    for (int i = 0; i < Settings.DEFAULT_INITIAL_WINDOW_SIZE / 1024; i++) {
      peer.acceptFrame(); // DATA
    }
    peer.play();

    // Play it back.
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    OutputStream out = stream.getOutputStream();
    out.write(new byte[Settings.DEFAULT_INITIAL_WINDOW_SIZE]);
    interruptAfterDelay(500);
    try {
      out.write('a');
      out.flush();
      fail();
    } catch (InterruptedIOException expected) {
    }

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(TYPE_DATA, data.type);
  }

  @Test public void testTruncatedDataFrame() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, Arrays.asList("a", "android"));
    peer.sendTruncatedFrame(8 + 100).data(false, 1, new byte[1024]);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
    InputStream in = stream.getInputStream();
    try {
      Util.readFully(in, new byte[101]);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }
  }

  /** https://github.com/square/okhttp/issues/333 */
  @Test public void nameValueBlockHasTrailingCompressedBytes() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    // This specially-formatted frame has trailing deflated bytes after the name value block.
    String frame = "gAMAAgAAAgkAAAABeLvjxqfCYgAAAAD//2IAAAAA//9iAAAAAP//YgQAAAD//2IAAAAA//9iAAAAAP/"
        + "/YgAAAAD//2IEAAAA//9KBAAAAP//YgAAAAD//2IAAAAA//9iAAAAAP//sgEAAAD//2IAAAAA\n//9iBAAAAP//Y"
        + "gIAAAD//2IGAAAA//9iAQAAAP//YgUAAAD//2IDAAAA//9iBwAAAP//4gAAAAD//+IEAAAA///iAgAAAP//4gYAA"
        + "AD//+IBAAAA///iBQAAAP//4gMAAAD//+IHAAAA//8SAAAAAP//EgQAAAD//xICAAAA//8SBgAAAP//EgEAAAD//"
        + "xIFAAAA//8SAwAAAP//EgcAAAD//5IAAAAA//+SBAAAAP//kgIAAAD//5IGAAAA//+SAQAAAP//kgUAAAD//5IDA"
        + "AAA//+SBwAAAP//UgAAAAD//1IEAAAA//9SAgAAAP//UgYAAAD//1IBAAAA//9SBQAAAP//UgMAAAD//1IHAAAA/"
        + "//SAAAAAP//0gQAAAD//9ICAAAA///SBgAAAP//0gEAAAD//9IFAAAA///SAwAAAP//0gcAAAD//zIAAAAA//8yB"
        + "AAAAP//MgIAAAD//zIGAAAA//8yAQAAAP//MgUAAAD//zIDAAAA//8yBwAAAP//sgAAAAD//7IEAAAA//+yAgAAA"
        + "P//sgYAAAD//w==";
    peer.sendFrame(Base64.decode(frame.getBytes(UTF_8)));
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
    assertEquals("a", stream.getResponseHeaders().get(0));
    assertEquals(60, stream.getResponseHeaders().get(1).length());
    assertStreamData("robot", stream.getInputStream());
  }

  private void writeAndClose(SpdyStream stream, String data) throws IOException {
    OutputStream out = stream.getOutputStream();
    out.write(data.getBytes("UTF-8"));
    out.close();
  }

  private void assertStreamData(String expected, InputStream inputStream) throws IOException {
    ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    for (int count; (count = inputStream.read(buffer)) != -1; ) {
      bytesOut.write(buffer, 0, count);
    }
    String actual = bytesOut.toString("UTF-8");
    assertEquals(expected, actual);
  }

  /** Interrupts the current thread after {@code delayMillis}. */
  private void interruptAfterDelay(final long delayMillis) {
    final Thread toInterrupt = Thread.currentThread();
    new Thread("interrupting cow") {
      @Override public void run() {
        try {
          Thread.sleep(delayMillis);
          toInterrupt.interrupt();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
      }
    }.start();
  }
}
