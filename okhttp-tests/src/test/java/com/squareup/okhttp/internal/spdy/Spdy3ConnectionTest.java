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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;
import okio.Sink;
import okio.Source;
import org.junit.After;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static com.squareup.okhttp.internal.spdy.ErrorCode.CANCEL;
import static com.squareup.okhttp.internal.spdy.ErrorCode.INTERNAL_ERROR;
import static com.squareup.okhttp.internal.spdy.ErrorCode.INVALID_STREAM;
import static com.squareup.okhttp.internal.spdy.ErrorCode.PROTOCOL_ERROR;
import static com.squareup.okhttp.internal.spdy.ErrorCode.REFUSED_STREAM;
import static com.squareup.okhttp.internal.spdy.ErrorCode.STREAM_IN_USE;
import static com.squareup.okhttp.internal.spdy.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static com.squareup.okhttp.internal.spdy.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_DATA;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_GOAWAY;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_HEADERS;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_PING;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.spdy.Spdy3.TYPE_WINDOW_UPDATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class Spdy3ConnectionTest {
  private static final Variant SPDY3 = new Spdy3();
  private final MockSpdyPeer peer = new MockSpdyPeer();

  @After public void tearDown() throws Exception {
    peer.close();
  }

  @Test public void clientCreatesStreamAndServerReplies() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame()
        .synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().data(true, 1, new Buffer().writeUtf8("robot"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    assertStreamData("robot", stream.getSource());
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("c3po");
    out.close();
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
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), false, false);
    assertEquals(1, connection.openStreamCount());
    assertEquals(headerEntries("b", "banana"), stream.getResponseHeaders());
    connection.ping().roundTripTime(); // Ensure that inFinished has been received.
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    final List<Header> pushHeaders = headerEntries(
        ":scheme", "https",
        ":host", "localhost:8888",
        ":method", "GET",
        ":path", "/index.html",
        ":status", "200",
        ":version", "HTTP/1.1",
        "content-type", "text/html");
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, pushHeaders);
    peer.acceptFrame(); // SYN_REPLY
    peer.play();

    // play it back
    final AtomicInteger receiveCount = new AtomicInteger();
    IncomingStreamHandler handler = new IncomingStreamHandler() {
      @Override public void receive(SpdyStream stream) throws IOException {
        receiveCount.incrementAndGet();
        assertEquals(pushHeaders, stream.getRequestHeaders());
        assertEquals(null, stream.getErrorCode());
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
    // write the mocking script
    peer.sendFrame().synStream(false, false, 2, 0, headerEntries("a", "android"));
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

    connectionBuilder(peer, SPDY3).handler(handler).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame reply = peer.takeFrame();
    assertEquals(TYPE_HEADERS, reply.type);
    assertTrue(reply.inFinished);
    assertEquals(headerEntries("b", "banana"), reply.headerBlock);
    assertEquals(1, receiveCount.get());
    assertEquals(HeadersMode.SPDY_REPLY, reply.headersMode);
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

  @Test public void clearSettingsBeforeMerge() throws Exception {
    // write the mocking script
    Settings settings1 = new Settings();
    settings1.set(Settings.UPLOAD_BANDWIDTH, PERSIST_VALUE, 100);
    settings1.set(Settings.DOWNLOAD_BANDWIDTH, PERSIST_VALUE, 200);
    settings1.set(Settings.DOWNLOAD_RETRANS_RATE, 0, 300);
    peer.sendFrame().settings(settings1);
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame();
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);

    peer.takeFrame(); // Guarantees that the Settings frame has been processed.

    // fake a settings frame with clear flag set.
    Settings settings2 = new Settings();
    settings2.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 600);
    connection.readerRunnable.settings(true, settings2);

    synchronized (connection) {
      assertEquals(-1, connection.peerSettings.getUploadBandwidth(-1));
      assertEquals(-1, connection.peerSettings.getDownloadBandwidth(-1));
      assertEquals(-1, connection.peerSettings.getDownloadRetransRate(-1));
      assertEquals(600, connection.peerSettings.getMaxConcurrentStreams(-1));
    }
  }

  @Test public void bogusDataFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().data(true, 41, new Buffer().writeUtf8("bogus"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(41, rstStream.streamId);
    assertEquals(INVALID_STREAM, rstStream.errorCode);
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(2, ping.payload1);
  }

  @Test public void bogusReplyFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().synReply(false, 41, headerEntries("a", "android"));
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, SPDY3);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(41, rstStream.streamId);
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
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("square");
    out.flush();
    assertEquals(1, connection.openStreamCount());
    out.close();
    try {
      out.writeUtf8("round");
      fail();
    } catch (Exception expected) {
      assertEquals("closed", expected.getMessage());
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
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
    BufferedSink out = Okio.buffer(stream.getSink());
    connection.ping().roundTripTime(); // Ensure that the RST_CANCEL has been received.
    try {
      out.writeUtf8("square");
      out.flush();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }
    try {
      out.close();
      fail();
    } catch (IOException expected) {
      // Close throws because buffered data wasn't flushed.
    }
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
    BufferedSink out = Okio.buffer(stream.getSink());
    in.close();
    try {
      in.read(new Buffer(), 1);
      fail();
    } catch (IOException expected) {
      assertEquals("stream closed", expected.getMessage());
    }
    try {
      out.writeUtf8("a");
      out.flush();
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
    BufferedSink out = Okio.buffer(stream.getSink());
    source.close();
    try {
      source.read(new Buffer(), 1);
      fail();
    } catch (IOException expected) {
      assertEquals("stream closed", expected.getMessage());
    }
    out.writeUtf8("square");
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
    peer.sendFrame().data(true, 1, new Buffer().writeUtf8("square"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), false, true);
    Source source = stream.getSource();
    assertStreamData("square", source);
    connection.ping().roundTripTime(); // Ensure that inFinished has been received.
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
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("c", "cola"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    connection.ping().roundTripTime(); // Ensure that the 2nd SYN REPLY has been received.
    try {
      stream.getSource().read(new Buffer(), 1);
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
    peer.sendFrame().synStream(false, false, 2, 0, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.sendFrame().synStream(false, false, 2, 0, headerEntries("b", "banana"));
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
    peer.sendFrame().data(true, 1, new Buffer().writeUtf8("robot"));
    peer.sendFrame().data(true, 1, new Buffer().writeUtf8("c3po")); // Ignored.
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
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

  @Test public void clientDoesNotLimitFlowControl() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("b", "banana"));
    peer.sendFrame().data(false, 1, new Buffer().write(new byte[64 * 1024 + 1]));
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("a", "android"), true, true);
    assertEquals(headerEntries("b", "banana"), stream.getResponseHeaders());

    // verify the peer received what was expected
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(HeadersMode.SPDY_SYN_STREAM, synStream.headersMode);
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    peer.setVariantAndClient(SPDY3, false);

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // SYN_STREAM 3
    peer.sendFrame().goAway(1, PROTOCOL_ERROR, Util.EMPTY_BYTE_ARRAY);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 1
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream1 = connection.newStream(headerEntries("a", "android"), true, true);
    SpdyStream stream2 = connection.newStream(headerEntries("b", "banana"), true, true);
    connection.ping().roundTripTime(); // Ensure the GO_AWAY that resets stream2 has been received.
    BufferedSink sink1 = Okio.buffer(stream1.getSink());
    BufferedSink sink2 = Okio.buffer(stream2.getSink());
    sink1.writeUtf8("abc");
    try {
      sink2.writeUtf8("abc");
      sink2.flush();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: REFUSED_STREAM", expected.getMessage());
    }
    sink1.writeUtf8("def");
    sink1.close();
    try {
      connection.newStream(headerEntries("c", "cola"), true, true);
      fail();
    } catch (IOException expected) {
      assertEquals("shutdown", expected.getMessage());
    }
    assertTrue(stream1.isOpen());
    assertFalse(stream2.isOpen());
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
    peer.sendFrame().synStream(false, false, 2, 0, headerEntries("b", "b")); // Should be ignored!
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    BufferedSink sink = Okio.buffer(stream.getSink());
    try {
      sink.writeByte(0);
      sink.flush();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }
    try {
      stream.getSource().read(new Buffer(), 1);
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
    SpdyConnection connection = connection(peer, SPDY3);
    Ping ping = connection.ping();
    connection.close();
    assertEquals(-1, ping.roundTripTime());
  }

  @Test public void getResponseHeadersTimesOut() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS);
    long startNanos = System.nanoTime();
    try {
      stream.getResponseHeaders();
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void readTimesOut() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS);
    Source source = stream.getSource();
    long startNanos = System.nanoTime();
    try {
      source.read(new Buffer(), 1);
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void writeTimesOutAwaitingStreamWindow() throws Exception {
    // Set the peer's receive window to 5 bytes!
    Settings peerSettings = new Settings().set(Settings.INITIAL_WINDOW_SIZE, PERSIST_VALUE, 5);

    // write the mocking script
    peer.sendFrame().settings(peerSettings);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    connection.ping().roundTripTime(); // Make sure settings have been received.
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    Sink sink = stream.getSink();
    sink.write(new Buffer().writeUtf8("abcde"), 5);
    stream.writeTimeout().timeout(500, TimeUnit.MILLISECONDS);
    long startNanos = System.nanoTime();
    try {
      sink.write(new Buffer().writeUtf8("f"), 1); // This will time out waiting on the write window.
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(TYPE_PING, peer.takeFrame().type);
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(TYPE_DATA, peer.takeFrame().type);
    assertEquals(TYPE_RST_STREAM, peer.takeFrame().type);
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    SpdyConnection connection = connection(peer, SPDY3);
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
    peer.setVariantAndClient(SPDY3, false);

    int windowUpdateThreshold = DEFAULT_INITIAL_WINDOW_SIZE / 2;

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    for (int i = 0; i < 3; i++) {
      // Send frames summing to windowUpdateThreshold.
      for (int sent = 0, count; sent < windowUpdateThreshold; sent += count) {
        count = Math.min(SPDY3.maxFrameSize(), windowUpdateThreshold - sent);
        peer.sendFrame().data(false, 1, data(count));
      }
      peer.acceptFrame(); // connection WINDOW UPDATE
      peer.acceptFrame(); // stream WINDOW UPDATE
    }
    peer.sendFrame().data(true, 1, data(0));
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(0, stream.unacknowledgedBytesRead);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    while (in.read(buffer, 1024) != -1) {
      if (buffer.size() == 3 * windowUpdateThreshold) break;
    }
    assertEquals(-1, in.read(buffer, 1));

    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList(2);
      for (int j = 0; j < 2; j++) {
        MockSpdyPeer.InFrame windowUpdate = peer.takeFrame();
        assertEquals(TYPE_WINDOW_UPDATE, windowUpdate.type);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
      }
      assertTrue(windowUpdateStreamIds.contains(0)); // connection
      assertTrue(windowUpdateStreamIds.contains(1)); // stream
    }
  }

  private Buffer data(int byteCount) {
    return new Buffer().write(new byte[byteCount]);
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdate() throws Exception {
    peer.setVariantAndClient(SPDY3, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendFrame().data(true, 1, data(0));
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream client = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(3, peer.frameCount());
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdate() throws Exception {
    peer.setVariantAndClient(SPDY3, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream client = connection.newStream(headerEntries("b", "banana"), true, true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(Util.EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(TYPE_DATA, peer.takeFrame().type);
    assertEquals(3, peer.frameCount());
  }

  @Test public void testTruncatedDataFrame() throws Exception {
    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 1, headerEntries("a", "android"));
    peer.sendTruncatedFrame(8 + 100).data(false, 1, data(1024));
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    Source in = stream.getSource();
    try {
      Okio.buffer(in).readByteString(101);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }
  }

  @Test public void blockedStreamDoesntStarveNewStream() throws Exception {
    int framesThatFillWindow = roundUp(DEFAULT_INITIAL_WINDOW_SIZE, SPDY3.maxFrameSize());

    // Write the mocking script. This accepts more data frames than necessary!
    peer.acceptFrame(); // SYN_STREAM on stream 1
    for (int i = 0; i < framesThatFillWindow; i++) {
      peer.acceptFrame(); // DATA on stream 1
    }
    peer.acceptFrame(); // SYN_STREAM on stream 2
    peer.acceptFrame(); // DATA on stream 2
    peer.play();

    // Play it back.
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream1 = connection.newStream(headerEntries("a", "apple"), true, true);
    BufferedSink out1 = Okio.buffer(stream1.getSink());
    out1.write(new byte[DEFAULT_INITIAL_WINDOW_SIZE]);
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
    BufferedSink out2 = Okio.buffer(stream2.getSink());
    out2.writeUtf8("foo");
    out2.flush();

    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(1).bytesLeftInWriteWindow);
    assertEquals(DEFAULT_INITIAL_WINDOW_SIZE - 3, connection.getStream(3).bytesLeftInWriteWindow);
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
    byte[] trailingCompressedBytes = ByteString.decodeBase64(frame).toByteArray();
    trailingCompressedBytes[11] = 1; // Set SPDY/3 stream ID to 3.
    peer.sendFrame(trailingCompressedBytes);
    peer.sendFrame().data(true, 1, new Buffer().writeUtf8("robot"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    SpdyConnection connection = connection(peer, SPDY3);
    SpdyStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    assertEquals("a", stream.getResponseHeaders().get(0).name.utf8());
    assertEquals(length, stream.getResponseHeaders().get(0).value.size());
    assertStreamData("robot", stream.getSource());
  }

  private SpdyConnection connection(MockSpdyPeer peer, Variant variant) throws IOException {
    return connectionBuilder(peer, variant).build();
  }

  private SpdyConnection.Builder connectionBuilder(MockSpdyPeer peer, Variant variant)
      throws IOException {
    return new SpdyConnection.Builder(true, peer.openSocket())
        .protocol(variant.getProtocol());
  }

  private void assertStreamData(String expected, Source source) throws IOException {
    String actual = Okio.buffer(source).readUtf8();
    assertEquals(expected, actual);
  }

  private void assertFlushBlocks(BufferedSink out) throws IOException {
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
