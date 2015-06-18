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
package com.squareup.okhttp.internal.framed;

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import org.junit.After;
import org.junit.Test;

import static com.squareup.okhttp.TestUtil.headerEntries;
import static com.squareup.okhttp.internal.framed.ErrorCode.CANCEL;
import static com.squareup.okhttp.internal.framed.ErrorCode.PROTOCOL_ERROR;
import static com.squareup.okhttp.internal.framed.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static com.squareup.okhttp.internal.framed.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_DATA;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_HEADERS;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_PING;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_SETTINGS;
import static com.squareup.okhttp.internal.framed.Spdy3.TYPE_WINDOW_UPDATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class Http2ConnectionTest {
  private static final Variant HTTP_2 = new Http2();
  private final MockSpdyPeer peer = new MockSpdyPeer();

  @After public void tearDown() throws Exception {
    peer.close();
  }

  @Test public void serverPingsClientHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.sendFrame().ping(false, 2, 3);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connection(peer, HTTP_2);

    // verify the peer received what was expected
    MockSpdyPeer.InFrame ping = peer.takeFrame();
    assertEquals(TYPE_PING, ping.type);
    assertEquals(0, ping.streamId);
    assertEquals(2, ping.payload1);
    assertEquals(3, ping.payload2);
    assertTrue(ping.ack);
  }

  @Test public void clientPingsServerHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 5);
    peer.play();

    // play it back
    FramedConnection connection = connection(peer, HTTP_2);
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
    peer.setVariantAndClient(HTTP_2, false);

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

    FramedConnection connection = connection(peer, HTTP_2);

    // Default is 64KiB - 1.
    assertEquals(65535, connection.peerSettings.getInitialWindowSize(-1));

    // Verify the peer received the ACK.
    MockSpdyPeer.InFrame ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);
    ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);

    // This stream was created *after* the connection settings were adjusted.
    FramedStream stream = connection.newStream(headerEntries("a", "android"), false, true);

    assertEquals(3368, connection.peerSettings.getInitialWindowSize(DEFAULT_INITIAL_WINDOW_SIZE));
    assertEquals(1684, connection.bytesLeftInWriteWindow); // initial wasn't affected.
    // New Stream is has the most recent initial window size.
    assertEquals(3368, stream.bytesLeftInWriteWindow);
  }

  @Test public void peerHttp2ServerZerosCompressionTable() throws Exception {
    boolean client = false; // Peer is server, so we are client.
    Settings settings = new Settings();
    settings.set(Settings.HEADER_TABLE_SIZE, PERSIST_VALUE, 0);

    FramedConnection connection = sendHttp2SettingsAndCheckForAck(client, settings);

    // verify the peer's settings were read and applied.
    assertEquals(0, connection.peerSettings.getHeaderTableSize());
    Http2.Reader frameReader = (Http2.Reader) connection.readerRunnable.frameReader;
    assertEquals(0, frameReader.hpackReader.maxDynamicTableByteCount());
    // TODO: when supported, check the frameWriter's compression table is unaffected.
  }

  @Test public void peerHttp2ClientDisablesPush() throws Exception {
    boolean client = false; // Peer is client, so we are server.
    Settings settings = new Settings();
    settings.set(Settings.ENABLE_PUSH, 0, 0); // The peer client disables push.

    FramedConnection connection = sendHttp2SettingsAndCheckForAck(client, settings);

    // verify the peer's settings were read and applied.
    assertFalse(connection.peerSettings.getEnablePush(true));
  }

  @Test public void peerIncreasesMaxFrameSize() throws Exception {
    int newMaxFrameSize = 0x4001;
    Settings settings = new Settings();
    settings.set(Settings.MAX_FRAME_SIZE, 0, newMaxFrameSize);

    FramedConnection connection = sendHttp2SettingsAndCheckForAck(true, settings);

    // verify the peer's settings were read and applied.
    assertEquals(newMaxFrameSize, connection.peerSettings.getMaxFrameSize(-1));
    assertEquals(newMaxFrameSize, connection.frameWriter.maxDataLength());
  }

  @Test public void receiveGoAwayHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM 3
    peer.acceptFrame(); // SYN_STREAM 5
    peer.sendFrame().goAway(3, PROTOCOL_ERROR, Util.EMPTY_BYTE_ARRAY);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 3
    peer.play();

    // play it back
    FramedConnection connection = connection(peer, HTTP_2);
    FramedStream stream1 = connection.newStream(headerEntries("a", "android"), true, true);
    FramedStream stream2 = connection.newStream(headerEntries("b", "banana"), true, true);
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
    assertEquals(3, data1.streamId);
    assertTrue(Arrays.equals("abcdef".getBytes("UTF-8"), data1.data));
  }

  @Test public void readSendsWindowUpdateHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    int windowSize = 100;
    int windowUpdateThreshold = 50;

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    for (int i = 0; i < 3; i++) {
      // Send frames of summing to size 50, which is windowUpdateThreshold.
      peer.sendFrame().data(false, 3, data(24), 24);
      peer.sendFrame().data(false, 3, data(25), 25);
      peer.sendFrame().data(false, 3, data(1), 1);
      peer.acceptFrame(); // connection WINDOW UPDATE
      peer.acceptFrame(); // stream WINDOW UPDATE
    }
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    FramedConnection connection = connection(peer, HTTP_2);
    connection.okHttpSettings.set(Settings.INITIAL_WINDOW_SIZE, 0, windowSize);
    FramedStream stream = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(0, stream.unacknowledgedBytesRead);
    assertEquals(headerEntries("a", "android"), stream.getResponseHeaders());
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    buffer.writeAll(in);
    assertEquals(-1, in.read(buffer, 1));
    assertEquals(150, buffer.size());

    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList<>(2);
      for (int j = 0; j < 2; j++) {
        MockSpdyPeer.InFrame windowUpdate = peer.takeFrame();
        assertEquals(TYPE_WINDOW_UPDATE, windowUpdate.type);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
      }
      assertTrue(windowUpdateStreamIds.contains(0)); // connection
      assertTrue(windowUpdateStreamIds.contains(3)); // stream
    }
  }

  private Buffer data(int byteCount) {
    return new Buffer().write(new byte[byteCount]);
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdateHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    FramedConnection connection = connection(peer, HTTP_2);
    FramedStream client = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // Verify the peer received what was expected.
    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    assertEquals(3, peer.frameCount());
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdateHttp2() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // Write the mocking script.
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    FramedConnection connection = connection(peer, HTTP_2);
    FramedStream client = connection.newStream(headerEntries("b", "banana"), true, true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(Util.EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(TYPE_DATA, peer.takeFrame().type);
    assertEquals(3, peer.frameCount());
  }

  @Test public void maxFrameSizeHonored() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    byte[] buff = new byte[peer.maxOutboundDataLength() + 1];
    Arrays.fill(buff, (byte) '*');

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    FramedConnection connection = connection(peer, HTTP_2);
    FramedStream stream = connection.newStream(headerEntries("b", "banana"), true, true);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.write(buff);
    out.flush();
    out.close();

    MockSpdyPeer.InFrame synStream = peer.takeFrame();
    assertEquals(TYPE_HEADERS, synStream.type);
    MockSpdyPeer.InFrame data = peer.takeFrame();
    assertEquals(peer.maxOutboundDataLength(), data.data.length);
    data = peer.takeFrame();
    assertEquals(1, data.data.length);
  }

  @Test public void pushPromiseStream() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    final List<Header> expectedRequestHeaders = Arrays.asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/cached")
    );
    peer.sendFrame().pushPromise(3, 2, expectedRequestHeaders);
    final List<Header> expectedResponseHeaders = Arrays.asList(
        new Header(Header.RESPONSE_STATUS, "200")
    );
    peer.sendFrame().synReply(true, 2, expectedResponseHeaders);
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    RecordingPushObserver observer = new RecordingPushObserver();

    // play it back
    FramedConnection connection = connectionBuilder(peer, HTTP_2)
        .pushObserver(observer).build();
    FramedStream client = connection.newStream(headerEntries("b", "banana"), false, true);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // verify the peer received what was expected
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);

    assertEquals(expectedRequestHeaders, observer.takeEvent());
    assertEquals(expectedResponseHeaders, observer.takeEvent());
  }

  @Test public void doublePushPromise() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.sendFrame().pushPromise(3, 2, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.sendFrame().pushPromise(3, 2, headerEntries("b", "banana"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    FramedConnection connection = connectionBuilder(peer, HTTP_2).build();
    connection.newStream(headerEntries("b", "banana"), false, true);

    // verify the peer received what was expected
    assertEquals(TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(PROTOCOL_ERROR, peer.takeFrame().errorCode);
  }

  @Test public void pushPromiseStreamsAutomaticallyCancel() throws Exception {
    peer.setVariantAndClient(HTTP_2, false);

    // write the mocking script
    peer.sendFrame().pushPromise(3, 2, Arrays.asList(
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
    connectionBuilder(peer, HTTP_2)
        .pushObserver(PushObserver.CANCEL).build();

    // verify the peer received what was expected
    MockSpdyPeer.InFrame rstStream = peer.takeFrame();
    assertEquals(TYPE_RST_STREAM, rstStream.type);
    assertEquals(2, rstStream.streamId);
    assertEquals(CANCEL, rstStream.errorCode);
  }

  private FramedConnection sendHttp2SettingsAndCheckForAck(boolean client, Settings settings)
      throws IOException, InterruptedException {
    peer.setVariantAndClient(HTTP_2, client);
    peer.sendFrame().settings(settings);
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    FramedConnection connection = connection(peer, HTTP_2);

    // verify the peer received the ACK
    MockSpdyPeer.InFrame ackFrame = peer.takeFrame();
    assertEquals(TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);

    connection.ping().roundTripTime(); // Ensure that settings have been applied before returning.
    return connection;
  }

  private FramedConnection connection(MockSpdyPeer peer, Variant variant) throws IOException {
    return connectionBuilder(peer, variant).build();
  }

  private FramedConnection.Builder connectionBuilder(MockSpdyPeer peer, Variant variant)
      throws IOException {
    return new FramedConnection.Builder(true, peer.openSocket())
        .pushObserver(IGNORE)
        .protocol(variant.getProtocol());
  }

  static final PushObserver IGNORE = new PushObserver() {

    @Override public boolean onRequest(int streamId, List<Header> requestHeaders) {
      return false;
    }

    @Override public boolean onHeaders(int streamId, List<Header> responseHeaders, boolean last) {
      return false;
    }

    @Override public boolean onData(int streamId, BufferedSource source, int byteCount,
        boolean last) throws IOException {
      source.skip(byteCount);
      return false;
    }

    @Override public void onReset(int streamId, ErrorCode errorCode) {
    }
  };

  private static class RecordingPushObserver implements PushObserver {
    final List<Object> events = new ArrayList<>();

    public synchronized Object takeEvent() throws InterruptedException {
      while (events.isEmpty()) {
        wait();
      }
      return events.remove(0);
    }

    @Override public synchronized boolean onRequest(int streamId, List<Header> requestHeaders) {
      assertEquals(2, streamId);
      events.add(requestHeaders);
      notifyAll();
      return false;
    }

    @Override public synchronized boolean onHeaders(
        int streamId, List<Header> responseHeaders, boolean last) {
      assertEquals(2, streamId);
      assertTrue(last);
      events.add(responseHeaders);
      notifyAll();
      return false;
    }

    @Override public synchronized boolean onData(
        int streamId, BufferedSource source, int byteCount, boolean last) {
      events.add(new AssertionError("onData"));
      notifyAll();
      return false;
    }

    @Override public synchronized void onReset(int streamId, ErrorCode errorCode) {
      events.add(new AssertionError("onReset"));
      notifyAll();
    }
  }
}
