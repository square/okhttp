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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.BufferedSource;
import com.squareup.okhttp.internal.bytes.ByteString;
import com.squareup.okhttp.internal.bytes.Deadline;
import com.squareup.okhttp.internal.bytes.OkBuffer;
import com.squareup.okhttp.internal.bytes.Source;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static com.squareup.okhttp.internal.Util.headerEntries;
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
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_PING;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_SETTINGS;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_WINDOW_UPDATE;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.INITIAL_WINDOW_SIZE;
import static com.squareup.okhttp.internal.spdy.SpdyStream.OUTPUT_BUFFER_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SpdyConnectionTest {
  private static final Variant SPDY3 = new Spdy3();
  private static final Variant HTTP_20_DRAFT_09 = new Http20Draft09();

  private static final IncomingStreamHandler REJECT_INCOMING_STREAMS = new IncomingStreamHandler() {
    @Override public void receive(SpdyStream stream) throws IOException {
      throw new AssertionError();
    }
  };
  private final MockSpdyPeer peer = new MockSpdyPeer();

  @After public void tearDown() throws Exception {
    peer.close();
  }

  @Test public void clientCreatesStreamAndServerReplies() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame()
        .synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    assertStreamData("robot", stream.getSource());
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
    assertEquals(headerEntries("b", "banana"), synStream.headerBlock);
    MockSpdyPeer.InFrame requestData = peer.takeFrame();
    assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
  }

  @Test public void headersOnlyStreamIsClosedAfterReplyHeaders() throws Exception {
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.play();

    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), false, false);
    assertEquals(1, connection.openStreamCount());
    assertEquals(headerEntries("b", "banana"), stream.getResponseHeaders());
    assertEquals(0, connection.openStreamCount());
  }

  @Test public void clientCreatesStreamAndServerRepliesWithFin() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(true, 1, headerEntries("a", "android"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    connection.newStream(headerEntries("b", "banana"), false, true);
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
    peer.sendFrame().synStream(false, false, 2, 0, 5, 129, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        receiveCount.incrementAndGet();
        assertEquals(headerEntries("a", "android"), stream.getRequestHeaders());
        assertEquals(null, stream.getErrorCode());
        assertEquals(5, stream.getPriority());
        stream.reply(headerEntries("b", "banana"), true);
      }
    };
    new SpdyConnection.Builder(true, peer.openSocket()).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
    assertFalse(reply.inFinished);
    assertEquals(2, reply.streamId);
    assertEquals(headerEntries("b", "banana"), reply.headerBlock);
    assertEquals(1, receiveCount.get());
  }

  @Test public void replyWithNoData() throws Exception {
    MockSpdyPeer.InFrame reply = replyWithNoData(SPDY3);
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
  }

  @Test public void replyWithNoDataHttp2() throws Exception {
    MockSpdyPeer.InFrame reply = replyWithNoData(HTTP_20_DRAFT_09);
    assertEquals(HeadersMode.HTTP_20_HEADERS, reply.headersMode);
  }

  private MockSpdyPeer.InFrame replyWithNoData(Variant variant) throws Exception {
    peer.setVariantAndClient(variant, false);
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        stream.reply(headerEntries("b", "banana"), false);
        receiveCount.incrementAndGet();
      }
    };
    connectionBuilder(peer, variant).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertTrue(reply.inFinished);
    assertEquals(headerEntries("b", "banana"), reply.headerBlock);
    assertEquals(1, receiveCount.get());
    return reply;
  }

  @Test public void serverPingsClient() throws Exception {
    // write the mocking script
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(0, ping.streamId);
    assertEquals(2, ping.payload1);
    assertEquals(0, ping.payload2); // ignored in spdy!
    assertTrue(ping.ack);
  }

  @Test public void serverPingsClientHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, false);

    // write the mocking script
    peer.sendFrame().ping(false, 2, 3);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, HTTP_20_DRAFT_09);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(0, ping.streamId);
    assertEquals(2, ping.payload1);
    assertEquals(3, ping.payload2);
    assertTrue(ping.ack);
  }

  @Test public void clientPingsServer() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 5); // payload2 ignored in spdy!
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    Ping ping = connection.ping();
    assertTrue(ping.roundTripTime() > 0);
    assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
    assertEquals(TYPE_PING, pingFrame.type);
    assertEquals(1, pingFrame.payload1);
    assertEquals(0, pingFrame.payload2);
    assertFalse(pingFrame.ack);
  }

  @Test public void clientPingsServerHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, false);

    // write the mocking script
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 5);
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, HTTP_20_DRAFT_09);
    Ping ping = connection.ping();
    assertTrue(ping.roundTripTime() > 0);
    assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
    assertEquals(0, pingFrame.streamId);
    assertEquals(1, pingFrame.payload1);
    assertEquals(0x4f4b6f6b, pingFrame.payload2); // connection.ping() sets this.
    assertFalse(pingFrame.ack);
  }

  @Test public void peerHttp2ServerLowersInitialWindowSize() throws Exception {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, false);

    Settings initial = new Settings();
    initial.set(Settings.INITIAL_WINDOW_SIZE, PERSIST_VALUE, 1684);
    Settings shouldntImpactConnection = new Settings();
    shouldntImpactConnection.set(Settings.INITIAL_WINDOW_SIZE, PERSIST_VALUE, 3368);

    peer.sendFrame().settings(initial);
    peer.acceptFrame(); // ACK
    peer.sendFrame().settings(shouldntImpactConnection);
    peer.acceptFrame(); // ACK 2
    peer.acceptFrame(); // HEADERS
    peer.play();

    SpdyConnection connection = connection(peer, HTTP_20_DRAFT_09);

    // verify the peer received the ACK
    MockSpdyPeer.InFrame ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);
    ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);

    // This stream was created *after* the connection settings were adjusted.
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);

    assertEquals(3368, connection.peerSettings.getInitialWindowSize());
    assertEquals(1684, connection.bytesLeftInWriteWindow); // initial wasn't affected.
    // New Stream is has the most recent initial window size.
    assertEquals(3368, stream.bytesLeftInWriteWindow);
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
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping2 = peer.takeFrame();
    assertEquals(2, ping2.payload1);
    MockSpdyPeer.InFrame ping4 = peer.takeFrame();
    assertEquals(4, ping4.payload1);
  }

  @Test public void peerHttp2ServerZerosCompressionTable() throws Exception {
    boolean client = false; // Peer is server, so we are client.
    Settings settings = new Settings();
    settings.set(Settings.HEADER_TABLE_SIZE, PERSIST_VALUE, 0);

    SpdyConnection connection = sendHttp2SettingsAndCheckForAck(client, settings);

    // verify the peer's settings were read and applied.
    synchronized (connection) {
      assertEquals(0, connection.peerSettings.getHeaderTableSize());
      Http20Draft09.Reader frameReader = (Http20Draft09.Reader) connection.frameReader;
      assertEquals(0, frameReader.hpackReader.maxHeaderTableByteCount());
      // TODO: when supported, check the frameWriter's compression table is unaffected.
    }
  }

  @Test public void peerHttp2ClientDisablesPush() throws Exception {
    boolean client = false; // Peer is client, so we are server.
    Settings settings = new Settings();
    settings.set(Settings.ENABLE_PUSH, 0, 0); // The peer client disables push.

    SpdyConnection connection = sendHttp2SettingsAndCheckForAck(client, settings);

    // verify the peer's settings were read and applied.
    synchronized (connection) {
      assertFalse(connection.peerSettings.getEnablePush(true));
    }
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
    SpdyConnection connection = connection(peer, SPDY3);

    peer.takeFrame(); // Guarantees that the peer Settings frame has been processed.
    synchronized (connection) {
      assertEquals(10, connection.peerSettings.getMaxConcurrentStreams(-1));
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
    SpdyConnection connection = connection(peer, SPDY3);

    peer.takeFrame(); // Guarantees that the Settings frame has been processed.
    synchronized (connection) {
      assertEquals(100, connection.peerSettings.getUploadBandwidth(-1));
      assertEquals(PERSIST_VALUE, connection.peerSettings.flags(Settings.UPLOAD_BANDWIDTH));
      assertEquals(400, connection.peerSettings.getDownloadBandwidth(-1));
      assertEquals(0, connection.peerSettings.flags(Settings.DOWNLOAD_BANDWIDTH));
      assertEquals(500, connection.peerSettings.getDownloadRetransRate(-1));
      assertEquals(PERSIST_VALUE, connection.peerSettings.flags(Settings.DOWNLOAD_RETRANS_RATE));
      assertEquals(600, connection.peerSettings.getMaxConcurrentStreams(-1));
      assertEquals(PERSIST_VALUE, connection.peerSettings.flags(Settings.MAX_CONCURRENT_STREAMS));
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
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(42, rstStream.streamId);
    assertEquals(INVALID_STREAM, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(2, ping.payload1);
  }

  @Test public void bogusReplyFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().synReply(false, 42, headerEntries("a", "android"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(42, rstStream.streamId);
    assertEquals(INVALID_STREAM, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(2, ping.payload1);
  }

  @Test public void clientClosesClientOutputStream() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.acceptFrame(); // TYPE_DATA
    peer.acceptFrame(); // TYPE_DATA with FLAG_FIN
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, false);
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
    assertEquals(1, ping.payload1);
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
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
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
    assertEquals(1, ping.payload1);
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
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), false, true);
    Source in = stream.getSource();
    OutputStream out = stream.getOutputStream();
    in.close(Deadline.NONE);
    try {
      in.read(new OkBuffer(), 1, Deadline.NONE);
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
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
    Source source = stream.getSource();
    OutputStream out = stream.getOutputStream();
    source.close(Deadline.NONE);
    try {
      source.read(new OkBuffer(), 1, Deadline.NONE);
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
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.sendFrame().data(true, 1, "square".getBytes(UTF_8));
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), false, true);
    Source source = stream.getSource();
    assertStreamData("square", source);
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
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("c", "cola"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    connection.ping().roundTripTime(); // Ensure that the 2nd SYN REPLY has been received.
    try {
      stream.getSource().read(new OkBuffer(), 1, Deadline.NONE);
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
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, headerEntries("b", "banana"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        receiveCount.incrementAndGet();
        assertEquals(headerEntries("a", "android"), stream.getRequestHeaders());
        assertEquals(null, stream.getErrorCode());
        stream.reply(headerEntries("c", "cola"), true);
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
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.sendFrame().data(true, 1, "c3po".getBytes("UTF-8")); // Ignored.
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    assertStreamData("robot", stream.getSource());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(2, ping.payload1);
  }

  @Test public void remoteSendsTooMuchData() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.sendFrame().data(false, 1, new byte[64 * 1024 + 1]);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
    assertEquals(headerEntries("b", "banana"), stream.getResponseHeaders());

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
    assertEquals(2, ping.payload1);
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
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
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
    assertEquals(2, ping.payload1);
  }


  @Test public void receiveGoAway() throws Exception {
    receiveGoAway(SPDY3);
  }

  @Test public void receiveGoAwayHttp2() throws Exception {
    receiveGoAway(HTTP_20_DRAFT_09);
  }

  private void receiveGoAway(Variant variant) throws Exception {
    peer.setVariantAndClient(variant, false);

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // SYN_STREAM 3
    peer.sendFrame().goAway(1, PROTOCOL_ERROR, Util.EMPTY_BYTE_ARRAY);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 1
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, variant);
    SpdyStream stream1 = connection.newStream(headerEntries("a", "android"), true, true);
    SpdyStream stream2 = connection.newStream(headerEntries("b", "banana"), true, true);
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
      connection.newStream(headerEntries("c", "cola"), true, true);
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
    peer.sendFrame().synStream(false, false, 2, 0, 0, 0, headerEntries("b", "b")); // Should be ignored!
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    connection.newStream(headerEntries("a", "android"), true, true);
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
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
    assertEquals(1, connection.openStreamCount());
    connection.close();
    assertEquals(0, connection.openStreamCount());
    try {
      connection.newStream(headerEntries("b", "banana"), true, true);
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
      stream.getSource().read(new OkBuffer(), 1, Deadline.NONE);
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
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    stream.setReadTimeout(1000);
    Source source = stream.getSource();
    long startNanos = System.nanoTime();
    try {
      source.read(new OkBuffer(), 1, Deadline.NONE);
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
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().headers(1, headerEntries("c", "c3po"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
    assertEquals(headerEntries("a", "android", "c", "c3po"), stream.getResponseHeaders());

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
    peer.sendFrame().headers(1, headerEntries("c", "c3po"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
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
    readSendsWindowUpdate(SPDY3);
  }

  @Test public void readSendsWindowUpdateHttp2() throws Exception {
    readSendsWindowUpdate(HTTP_20_DRAFT_09);
  }

  private void readSendsWindowUpdate(Variant variant)
      throws IOException, InterruptedException {
    peer.setVariantAndClient(variant, false);

    int windowUpdateThreshold = INITIAL_WINDOW_SIZE / 2;

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    for (int i = 0; i < 3; i++) {
      peer.sendFrame().data(false, 1, new byte[OUTPUT_BUFFER_SIZE]);
      peer.sendFrame().data(false, 1, new byte[OUTPUT_BUFFER_SIZE]);
      peer.sendFrame().data(false, 1, new byte[OUTPUT_BUFFER_SIZE]);
      peer.sendFrame().data(false, 1, new byte[windowUpdateThreshold - OUTPUT_BUFFER_SIZE * 3]);
      peer.acceptFrame(); // connection WINDOW UPDATE
      peer.acceptFrame(); // stream WINDOW UPDATE
    }
    peer.sendFrame().data(true, 1, new byte[0]);
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, variant);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(0, stream.unacknowledgedBytesRead);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    Source in = stream.getSource();
    OkBuffer buffer = new OkBuffer();
    while (in.read(buffer, 1024, Deadline.NONE) != -1) {
      if (buffer.byteCount() == 3 * windowUpdateThreshold) break;
    }
    assertEquals(-1, in.read(buffer, 1, Deadline.NONE));

    // Verify the peer received what was expected.
    assertEquals(21, peer.frameCount());

    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      MockSpdyPeer.InFrame windowUpdate = peer.takeFrame();
      assertEquals(TYPE_WINDOW_UPDATE, windowUpdate.type);
      assertEquals(1, windowUpdate.streamId);
      assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
      windowUpdate = peer.takeFrame();
      assertEquals(TYPE_WINDOW_UPDATE, windowUpdate.type);
      assertEquals(0, windowUpdate.streamId); // connection window update
      assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
    }
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdate() throws Exception {
    serverSendsEmptyDataClientDoesntSendWindowUpdate(SPDY3);
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdateHttp2() throws Exception {
    serverSendsEmptyDataClientDoesntSendWindowUpdate(HTTP_20_DRAFT_09);
  }

  private void serverSendsEmptyDataClientDoesntSendWindowUpdate(Variant variant)
      throws IOException, InterruptedException {
    peer.setVariantAndClient(variant, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().data(true, 1, new byte[0]);
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, variant);
    SpdyStream client = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(-1, client.getSource().read(new OkBuffer(), 1, Deadline.NONE));

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(3, peer.frameCount());
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdate() throws Exception {
    clientSendsEmptyDataServerDoesntSendWindowUpdate(SPDY3);
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdateHttp2() throws Exception {
    clientSendsEmptyDataServerDoesntSendWindowUpdate(HTTP_20_DRAFT_09);
  }

  private void clientSendsEmptyDataServerDoesntSendWindowUpdate(Variant variant)
      throws IOException, InterruptedException {
    peer.setVariantAndClient(variant, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, variant);
    SpdyStream client = connection.newStream(headerEntries("b", "banana"), true, true);
    client.getOutputStream().write(Util.EMPTY_BYTE_ARRAY);
    client.getOutputStream().flush();
    client.getOutputStream().close();

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(2, peer.frameCount());
  }

  @Test public void writeAwaitsWindowUpdate() throws Exception {
    int framesThatFillWindow = roundUp(INITIAL_WINDOW_SIZE, OUTPUT_BUFFER_SIZE);

    // Write the mocking script. This accepts more data frames than necessary!
    peer.acceptFrame(); // SYN_STREAM
    for (int i = 0; i < framesThatFillWindow; i++) {
      peer.acceptFrame(); // DATA
    }
    peer.acceptFrame(); // DATA we won't be able to flush until a window update.
    peer.play();

    // Play it back.
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    OutputStream out = stream.getOutputStream();
    out.write(new byte[INITIAL_WINDOW_SIZE]);
    out.flush();

    // Check that we've filled the window for both the stream and also the connection.
    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);

    out.write('a');
    assertFlushBlocks(out);

    // receiving a window update on the connection isn't enough.
    connection.readerRunnable.windowUpdate(0, 1);
    assertFlushBlocks(out);

    // receiving a window update on the stream will unblock the stream.
    connection.readerRunnable.windowUpdate(1, 1);
    out.flush();

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    for (int i = 0; i < framesThatFillWindow; i++) {
      MockSpdyPeer.InFrame data = peer.takeFrame();
      assertEquals(TYPE_DATA, data.type);
    }
  }

  @Test public void initialSettingsWithWindowSizeAdjustsConnection() throws Exception {
    int framesThatFillWindow = roundUp(INITIAL_WINDOW_SIZE, OUTPUT_BUFFER_SIZE);

    // Write the mocking script. This accepts more data frames than necessary!
    peer.acceptFrame(); // SYN_STREAM
    for (int i = 0; i < framesThatFillWindow; i++) {
      peer.acceptFrame(); // DATA on stream 1
    }
    peer.acceptFrame(); // DATA on stream 2
    peer.play();

    // Play it back.
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("a", "apple"), true, true);
    OutputStream out = stream.getOutputStream();
    out.write(new byte[INITIAL_WINDOW_SIZE]);
    out.flush();

    // write 1 more than the window size
    out.write('a');
    assertFlushBlocks(out);

    // Check that we've filled the window for both the stream and also the connection.
    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);

    // Receiving a Settings with a larger window size will unblock the streams.
    Settings initial = new Settings();
    initial.set(Settings.INITIAL_WINDOW_SIZE, PERSIST_VALUE, INITIAL_WINDOW_SIZE + 1);
    connection.readerRunnable.settings(false, initial);

    assertEquals(1, connection.bytesLeftInWriteWindow);
    assertEquals(1, connection.getStream(1).bytesLeftInWriteWindow);

    // The stream should no longer be blocked.
    out.flush();

    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);

    // Settings after the initial do not affect the connection window size.
    Settings next = new Settings();
    next.set(Settings.INITIAL_WINDOW_SIZE, PERSIST_VALUE, INITIAL_WINDOW_SIZE + 2);
    connection.readerRunnable.settings(false, next);

    assertEquals(0, connection.bytesLeftInWriteWindow); // connection wasn't affected.
    assertEquals(1, connection.getStream(1).bytesLeftInWriteWindow);
  }

  @Test public void testTruncatedDataFrame() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendTruncatedFrame(8 + 100).data(false, 1, new byte[1024]);
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    Source in = stream.getSource();
    try {
      new BufferedSource(in).readByteString(101);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }
  }

  @Test public void blockedStreamDoesntStarveNewStream() throws Exception {
    int framesThatFillWindow = roundUp(INITIAL_WINDOW_SIZE, SpdyStream.OUTPUT_BUFFER_SIZE);

    // Write the mocking script. This accepts more data frames than necessary!
    peer.acceptFrame(); // SYN_STREAM
    for (int i = 0; i < framesThatFillWindow; i++) {
      peer.acceptFrame(); // DATA on stream 1
    }
    peer.acceptFrame(); // DATA on stream 2
    peer.play();

    // Play it back.
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream1 = connection.newStream(headerEntries("a", "apple"), true, true);
    OutputStream out1 = stream1.getOutputStream();
    out1.write(new byte[INITIAL_WINDOW_SIZE]);
    out1.flush();

    // Check that we've filled the window for both the stream and also the connection.
    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);

    // receiving a window update on the the connection will unblock new streams.
    connection.readerRunnable.windowUpdate(0, 3);

    assertEquals(3, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);

    // Another stream should be able to send data even though 1 is blocked.
    SpdyStream stream2 = connection.newStream(headerEntries("b", "banana"), true, true);
    OutputStream out2 = stream2.getOutputStream();
    out2.write("foo".getBytes(UTF_8));
    out2.flush();

    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);
    assertEquals(INITIAL_WINDOW_SIZE - 3, connection.getStream(3).bytesLeftInWriteWindow);
  }

  /**
   * This tests that data frames are written in chunks limited by the
   * SpdyDataOutputStream buffer size.  A side-effect is that this size
   * prevents us from overrunning the max frame size of SPDY/3 or HTTP/2.
   */
  @Test public void spdyStreamOutputBufferSizeLimitsDataFrameLength() throws Exception {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, false);

    byte[] buff = new byte[SpdyStream.OUTPUT_BUFFER_SIZE * 2];
    Arrays.fill(buff, (byte) '*');

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA 1
    peer.acceptFrame(); // DATA 2
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, HTTP_20_DRAFT_09);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    OutputStream out = stream.getOutputStream();
    out.write(buff);
    out.flush();
    out.close();

    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(SpdyStream.OUTPUT_BUFFER_SIZE, data.data.length);
    data = peer.takeFrame();
    assertEquals(SpdyStream.OUTPUT_BUFFER_SIZE, data.data.length);
  }

  /** https://github.com/square/okhttp/issues/333 */
  @Test public void headerBlockHasTrailingCompressedBytes512() throws Exception {
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
    headerBlockHasTrailingCompressedBytes(frame, 60);
  }

  @Test public void headerBlockHasTrailingCompressedBytes2048() throws Exception {
    // This specially-formatted frame has trailing deflated bytes after the name value block.
    String frame = "gAMAAgAAB/sAAAABeLvjxqfCAqYjRhAGJmxGxUQAAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAA"
        + "AAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP/"
        + "/SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQ"
        + "AAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD"
        + "//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0o"
        + "EAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAA"
        + "A//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9"
        + "KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAA"
        + "AAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP/"
        + "/SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQ"
        + "AAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD"
        + "//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0o"
        + "EAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAA"
        + "A//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9"
        + "KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAA"
        + "AAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP/"
        + "/SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQ"
        + "AAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD"
        + "//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0o"
        + "EAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAA"
        + "A//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9"
        + "KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAA"
        + "AAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP/"
        + "/SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQ"
        + "AAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD"
        + "//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0o"
        + "EAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAA"
        + "A//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9"
        + "KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAA"
        + "AAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP/"
        + "/SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQAAAD//0oEAAAA//9KBAAAAP//SgQ"
        + "AAAD//0oEAAAA//8=";
    headerBlockHasTrailingCompressedBytes(frame, 289);
  }

  private void headerBlockHasTrailingCompressedBytes(String frame, int length) throws IOException {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame(ByteString.decodeBase64(frame).toByteArray());
    peer.sendFrame().data(true, 1, "robot".getBytes("UTF-8"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals("a", stream.getResponseHeaders().get(0).name.utf8());
    assertEquals(length, stream.getResponseHeaders().get(0).value.size());
    assertStreamData("robot", stream.getSource());
  }

  // TODO: change this to only cancel when local settings disable push
  @Test public void pushPromiseStreamsAutomaticallyCancel() throws Exception {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, false);

    // write the mocking script
    peer.sendFrame().pushPromise(1, 2, Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/cached")
    ));
    peer.sendFrame().synReply(true, 2, Arrays.asList(
        new Header(Header.RESPONSE_STATUS, "200")
    ));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    connection(peer, HTTP_20_DRAFT_09);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(2, rstStream.streamId);
    assertEquals(CANCEL, rstStream.errorCode);
  }

  private SpdyConnection sendHttp2SettingsAndCheckForAck(boolean client, Settings settings)
      throws IOException, InterruptedException {
    peer.setVariantAndClient(HTTP_20_DRAFT_09, client);
    peer.sendFrame().settings(settings);
    peer.acceptFrame(); // ACK
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, HTTP_20_DRAFT_09);

    // verify the peer received the ACK
    MockSpdyPeer.InFrame ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);
    return connection;
  }

  private SpdyConnection connection(MockSpdyPeer peer, Variant variant) throws IOException {
    return connectionBuilder(peer, variant).handler(REJECT_INCOMING_STREAMS).build();
  }

  private SpdyConnection.Builder connectionBuilder(MockSpdyPeer peer, Variant variant)
      throws IOException {
    return new SpdyConnection.Builder(true, peer.openSocket()).protocol(variant.getProtocol());
  }

  private void writeAndClose(SpdyStream stream, String data) throws IOException {
    OutputStream out = stream.getOutputStream();
    out.write(data.getBytes("UTF-8"));
    out.close();
  }

  private void assertStreamData(String expected, Source source) throws IOException {
    OkBuffer buffer = new OkBuffer();
    while (source.read(buffer, Long.MAX_VALUE, Deadline.NONE) != -1) {
    }
    String actual = buffer.readUtf8((int) buffer.byteCount());
    assertEquals(expected, actual);
  }

  private void assertFlushBlocks(OutputStream out) throws IOException {
    interruptAfterDelay(500);
    try {
      out.flush();
      fail();
    } catch (InterruptedIOException expected) {
    }
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

  static int roundUp(int num, int divisor) {
    return (num + divisor - 1) / divisor;
  }
}
