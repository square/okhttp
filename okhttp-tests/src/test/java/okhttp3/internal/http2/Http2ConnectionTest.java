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
package okhttp3.internal.http2;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.internal.Util;
import okhttp3.internal.http2.MockHttp2Peer.InFrame;
import okio.AsyncTimeout;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static okhttp3.TestUtil.headerEntries;
import static okhttp3.TestUtil.repeat;
import static okhttp3.internal.Util.EMPTY_BYTE_ARRAY;
import static okhttp3.internal.http2.Http2Connection.Listener.REFUSE_INCOMING_STREAMS;
import static okhttp3.internal.http2.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static okhttp3.internal.http2.Settings.ENABLE_PUSH;
import static okhttp3.internal.http2.Settings.HEADER_TABLE_SIZE;
import static okhttp3.internal.http2.Settings.INITIAL_WINDOW_SIZE;
import static okhttp3.internal.http2.Settings.MAX_CONCURRENT_STREAMS;
import static okhttp3.internal.http2.Settings.MAX_FRAME_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class Http2ConnectionTest {
  private final MockHttp2Peer peer = new MockHttp2Peer();

  @Rule public final TestRule timeout = new Timeout(5_000);

  @After public void tearDown() throws Exception {
    peer.close();
  }

  @Test public void serverPingsClientHttp2() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().ping(false, 2, 3);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    assertEquals(0, ping.streamId);
    assertEquals(2, ping.payload1);
    assertEquals(3, ping.payload2);
    assertTrue(ping.ack);
  }

  @Test public void clientPingsServerHttp2() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 5);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Ping ping = connection.ping();
    assertTrue(ping.roundTripTime() > 0);
    assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    InFrame pingFrame = peer.takeFrame();
    assertEquals(0, pingFrame.streamId);
    assertEquals(1, pingFrame.payload1);
    assertEquals(0x4f4b6f6b, pingFrame.payload2); // connection.ping() sets this.
    assertFalse(pingFrame.ack);
  }

  @Test public void peerHttp2ServerLowersInitialWindowSize() throws Exception {
    Settings initial = new Settings();
    initial.set(INITIAL_WINDOW_SIZE, 1684);
    Settings shouldntImpactConnection = new Settings();
    shouldntImpactConnection.set(INITIAL_WINDOW_SIZE, 3368);

    peer.sendFrame().settings(initial);
    peer.acceptFrame(); // ACK
    peer.sendFrame().settings(shouldntImpactConnection);
    peer.acceptFrame(); // ACK 2
    peer.acceptFrame(); // HEADERS
    peer.play();

    Http2Connection connection = connect(peer);

    // Verify the peer received the second ACK.
    InFrame ackFrame = peer.takeFrame();
    assertEquals(Http2.TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);

    // This stream was created *after* the connection settings were adjusted.
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);

    assertEquals(3368, connection.peerSettings.getInitialWindowSize());
    assertEquals(1684, connection.bytesLeftInWriteWindow); // initial wasn't affected.
    // New Stream is has the most recent initial window size.
    assertEquals(3368, stream.bytesLeftInWriteWindow);
  }

  @Test public void peerHttp2ServerZerosCompressionTable() throws Exception {
    boolean client = false; // Peer is server, so we are client.
    Settings settings = new Settings();
    settings.set(HEADER_TABLE_SIZE, 0);

    Http2Connection connection = connectWithSettings(client, settings);

    // Verify the peer's settings were read and applied.
    assertEquals(0, connection.peerSettings.getHeaderTableSize());
    Http2Writer writer = connection.writer;
    assertEquals(0, writer.hpackWriter.dynamicTableByteCount);
    assertEquals(0, writer.hpackWriter.headerTableSizeSetting);
  }

  @Test public void peerHttp2ClientDisablesPush() throws Exception {
    boolean client = false; // Peer is client, so we are server.
    Settings settings = new Settings();
    settings.set(ENABLE_PUSH, 0); // The peer client disables push.

    Http2Connection connection = connectWithSettings(client, settings);

    // verify the peer's settings were read and applied.
    assertFalse(connection.peerSettings.getEnablePush(true));
  }

  @Test public void peerIncreasesMaxFrameSize() throws Exception {
    int newMaxFrameSize = 0x4001;
    Settings settings = new Settings();
    settings.set(MAX_FRAME_SIZE, newMaxFrameSize);

    Http2Connection connection = connectWithSettings(true, settings);

    // verify the peer's settings were read and applied.
    assertEquals(newMaxFrameSize, connection.peerSettings.getMaxFrameSize(-1));
    assertEquals(newMaxFrameSize, connection.writer.maxDataLength());
  }

  /**
   * Webservers may set the initial window size to zero, which is a special case because it means
   * that we have to flush headers immediately before any request body can be sent.
   * https://github.com/square/okhttp/issues/2543
   */
  @Test public void peerSetsZeroFlowControl() throws Exception {
    peer.setClient(true);

    // Write the mocking script.
    peer.sendFrame().settings(new Settings().set(INITIAL_WINDOW_SIZE, 0));
    peer.acceptFrame(); // ACK
    peer.sendFrame().windowUpdate(0, 10); // Increase the connection window size.
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // HEADERS STREAM 3
    peer.sendFrame().windowUpdate(3, 5);
    peer.acceptFrame(); // DATA STREAM 3 "abcde"
    peer.sendFrame().windowUpdate(3, 5);
    peer.acceptFrame(); // DATA STREAM 3 "fghi"
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    connection.ping().roundTripTime(); // Ensure the SETTINGS have been received.
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
    BufferedSink sink = Okio.buffer(stream.getSink());
    sink.writeUtf8("abcdefghi");
    sink.flush();

    // Verify the peer received what was expected.
    peer.takeFrame(); // PING
    InFrame headers = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, headers.type);
    InFrame data1 = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data1.type);
    assertEquals(3, data1.streamId);
    assertTrue(Arrays.equals("abcde".getBytes("UTF-8"), data1.data));
    InFrame data2 = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data2.type);
    assertEquals(3, data2.streamId);
    assertTrue(Arrays.equals("fghi".getBytes("UTF-8"), data2.data));
  }

  @Test public void receiveGoAwayHttp2() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM 3
    peer.acceptFrame(); // SYN_STREAM 5
    peer.sendFrame().goAway(3, ErrorCode.PROTOCOL_ERROR, EMPTY_BYTE_ARRAY);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 3
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream1 = connection.newStream(headerEntries("a", "android"), true);
    Http2Stream stream2 = connection.newStream(headerEntries("b", "banana"), true);
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
      connection.newStream(headerEntries("c", "cola"), true);
      fail();
    } catch (ConnectionShutdownException expected) {
    }
    assertTrue(stream1.isOpen());
    assertFalse(stream2.isOpen());
    assertEquals(1, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream1.type);
    InFrame synStream2 = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream2.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    InFrame data1 = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data1.type);
    assertEquals(3, data1.streamId);
    assertTrue(Arrays.equals("abcdef".getBytes("UTF-8"), data1.data));
  }

  @Test public void readSendsWindowUpdateHttp2() throws Exception {
    int windowSize = 100;
    int windowUpdateThreshold = 50;

    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
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
    Http2Connection connection = connect(peer);
    connection.okHttpSettings.set(INITIAL_WINDOW_SIZE, windowSize);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(0, stream.unacknowledgedBytesRead);
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    buffer.writeAll(in);
    assertEquals(-1, in.read(buffer, 1));
    assertEquals(150, buffer.size());

    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList<>(2);
      for (int j = 0; j < 2; j++) {
        InFrame windowUpdate = peer.takeFrame();
        assertEquals(Http2.TYPE_WINDOW_UPDATE, windowUpdate.type);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
      }
      assertTrue(windowUpdateStreamIds.contains(0)); // connection
      assertTrue(windowUpdateStreamIds.contains(3)); // stream
    }
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdateHttp2() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // Verify the peer received what was expected.
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertEquals(5, peer.frameCount());
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdateHttp2() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_DATA, peer.takeFrame().type);
    assertEquals(5, peer.frameCount());
  }

  @Test public void maxFrameSizeHonored() throws Exception {
    byte[] buff = new byte[peer.maxOutboundDataLength() + 1];
    Arrays.fill(buff, (byte) '*');

    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.write(buff);
    out.flush();
    out.close();

    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame data = peer.takeFrame();
    assertEquals(peer.maxOutboundDataLength(), data.data.length);
    data = peer.takeFrame();
    assertEquals(1, data.data.length);
  }

  @Test public void pushPromiseStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
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
    Http2Connection connection = connect(peer, observer, REFUSE_INCOMING_STREAMS);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);

    assertEquals(expectedRequestHeaders, observer.takeEvent());
    assertEquals(expectedResponseHeaders, observer.takeEvent());
  }

  @Test public void doublePushPromise() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().pushPromise(3, 2, headerEntries("a", "android"));
    peer.acceptFrame(); // SYN_REPLY
    peer.sendFrame().pushPromise(3, 2, headerEntries("b", "banana"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.newStream(headerEntries("b", "banana"), false);

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(ErrorCode.PROTOCOL_ERROR, peer.takeFrame().errorCode);
  }

  @Test public void pushPromiseStreamsAutomaticallyCancel() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
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
    connect(peer, PushObserver.CANCEL, REFUSE_INCOMING_STREAMS);

    // verify the peer received what was expected
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(2, rstStream.streamId);
    assertEquals(ErrorCode.CANCEL, rstStream.errorCode);
  }

  /**
   * When writing a set of headers fails due to an {@code IOException}, make sure the writer is left
   * in a consistent state so the next writer also gets an {@code IOException} also instead of
   * something worse (like an {@link IllegalStateException}.
   *
   * <p>See https://github.com/square/okhttp/issues/1651
   */
  @Test public void socketExceptionWhileWritingHeaders() throws Exception {
    peer.acceptFrame(); // SYN_STREAM.
    peer.play();

    String longString = repeat('a', Http2.INITIAL_MAX_FRAME_SIZE + 1);
    Socket socket = peer.openSocket();
    Http2Connection connection = new Http2Connection.Builder(true)
        .socket(socket)
        .pushObserver(IGNORE)
        .build();
    connection.start(false);
    socket.shutdownOutput();
    try {
      connection.newStream(headerEntries("a", longString), false);
      fail();
    } catch (IOException expected) {
    }
    try {
      connection.newStream(headerEntries("b", longString), false);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void clientCreatesStreamAndServerReplies() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("robot"), 5);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("c3po");
    out.close();
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    assertStreamData("robot", stream.getSource());
    connection.ping().roundTripTime();
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertFalse(synStream.outFinished);
    assertEquals(3, synStream.streamId);
    assertEquals(-1, synStream.associatedStreamId);
    assertEquals(headerEntries("b", "banana"), synStream.headerBlock);
    InFrame requestData = peer.takeFrame();
    assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
  }

  @Test public void clientCreatesStreamAndServerRepliesWithFin() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(true, 3, headerEntries("a", "android"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(1, connection.openStreamCount());
    connection.ping().roundTripTime(); // Ensure that the SYN_REPLY has been received.
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
  }

  @Test public void serverPingsClient() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame ping = peer.takeFrame();
    assertEquals(0, ping.streamId);
    assertEquals(2, ping.payload1);
    assertEquals(0, ping.payload2);
    assertTrue(ping.ack);
  }

  @Test public void clientPingsServer() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 5);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Ping ping = connection.ping();
    assertTrue(ping.roundTripTime() > 0);
    assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    InFrame pingFrame = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, pingFrame.type);
    assertEquals(1, pingFrame.payload1);
    assertEquals(new Buffer().writeUtf8("OKok").readInt(), pingFrame.payload2);
    assertFalse(pingFrame.ack);
  }

  @Test public void unexpectedPingIsNotReturned() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 3, 0); // This ping will not be returned.
    peer.sendFrame().ping(false, 4, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame ping2 = peer.takeFrame();
    assertEquals(2, ping2.payload1);
    InFrame ping4 = peer.takeFrame();
    assertEquals(4, ping4.payload1);
  }

  @Test public void serverSendsSettingsToClient() throws Exception {
    // write the mocking script
    final Settings settings = new Settings();
    settings.set(MAX_CONCURRENT_STREAMS, 10);
    peer.sendFrame().settings(settings);
    peer.acceptFrame(); // ACK
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    final CountDownLatch maxConcurrentStreamsUpdated = new CountDownLatch(1);
    final AtomicInteger maxConcurrentStreams = new AtomicInteger();
    Http2Connection.Listener listener = new Http2Connection.Listener() {
      @Override public void onStream(Http2Stream stream) throws IOException {
        throw new AssertionError();
      }

      @Override public void onSettings(Http2Connection connection) {
        maxConcurrentStreams.set(connection.maxConcurrentStreams());
        maxConcurrentStreamsUpdated.countDown();
      }
    };
    Http2Connection connection = connect(peer, IGNORE, listener);

    synchronized (connection) {
      assertEquals(10, connection.peerSettings.getMaxConcurrentStreams(-1));
    }
    maxConcurrentStreamsUpdated.await();
    assertEquals(10, maxConcurrentStreams.get());
  }

  @Test public void multipleSettingsFramesAreMerged() throws Exception {
    // write the mocking script
    Settings settings1 = new Settings();
    settings1.set(HEADER_TABLE_SIZE, 10000);
    settings1.set(INITIAL_WINDOW_SIZE, 20000);
    settings1.set(MAX_FRAME_SIZE, 30000);
    peer.sendFrame().settings(settings1);
    peer.acceptFrame(); // ACK SETTINGS
    Settings settings2 = new Settings();
    settings2.set(INITIAL_WINDOW_SIZE, 40000);
    settings2.set(MAX_FRAME_SIZE, 50000);
    settings2.set(MAX_CONCURRENT_STREAMS, 60000);
    peer.sendFrame().settings(settings2);
    peer.acceptFrame(); // ACK SETTINGS
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);

    assertEquals(Http2.TYPE_SETTINGS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_PING, peer.takeFrame().type);
    synchronized (connection) {
      assertEquals(10000, connection.peerSettings.getHeaderTableSize());
      assertEquals(40000, connection.peerSettings.getInitialWindowSize());
      assertEquals(50000, connection.peerSettings.getMaxFrameSize(-1));
      assertEquals(60000, connection.peerSettings.getMaxConcurrentStreams(-1));
    }
  }

  @Test public void clearSettingsBeforeMerge() throws Exception {
    // write the mocking script
    Settings settings1 = new Settings();
    settings1.set(HEADER_TABLE_SIZE, 10000);
    settings1.set(INITIAL_WINDOW_SIZE, 20000);
    settings1.set(MAX_FRAME_SIZE, 30000);
    peer.sendFrame().settings(settings1);
    peer.acceptFrame(); // ACK
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame();
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);

    // fake a settings frame with clear flag set.
    Settings settings2 = new Settings();
    settings2.set(MAX_CONCURRENT_STREAMS, 60000);
    connection.readerRunnable.settings(true, settings2);

    synchronized (connection) {
      assertEquals(-1, connection.peerSettings.getHeaderTableSize());
      assertEquals(DEFAULT_INITIAL_WINDOW_SIZE, connection.peerSettings.getInitialWindowSize());
      assertEquals(-1, connection.peerSettings.getMaxFrameSize(-1));
      assertEquals(60000, connection.peerSettings.getMaxConcurrentStreams(-1));
    }
  }

  @Test public void bogusDataFrameDoesNotDisruptConnection() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().data(true, 41, new Buffer().writeUtf8("bogus"), 5);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(41, rstStream.streamId);
    assertEquals(ErrorCode.PROTOCOL_ERROR, rstStream.errorCode);
    InFrame ping = peer.takeFrame();
    assertEquals(2, ping.payload1);
  }

  @Test public void bogusReplySilentlyIgnored() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().synReply(false, 41, headerEntries("a", "android"));
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame ping = peer.takeFrame();
    assertEquals(2, ping.payload1);
  }

  @Test public void serverClosesClientOutputStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.CANCEL);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
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
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertFalse(synStream.inFinished);
    assertFalse(synStream.outFinished);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    assertEquals(1, ping.payload1);
  }

  /**
   * Test that the client sends a RST_STREAM if doing so won't disrupt the output stream.
   */
  @Test public void clientClosesClientInputStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
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
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertTrue(synStream.inFinished);
    assertFalse(synStream.outFinished);
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(ErrorCode.CANCEL, rstStream.errorCode);
  }

  /**
   * Test that the client doesn't send a RST_STREAM if doing so will disrupt the output stream.
   */
  @Test public void clientClosesClientInputStreamIfOutputStreamIsClosed() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // DATA with FLAG_FIN
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
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
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertFalse(synStream.inFinished);
    assertFalse(synStream.outFinished);
    InFrame data = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data.type);
    assertTrue(Arrays.equals("square".getBytes("UTF-8"), data.data));
    InFrame fin = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, fin.type);
    assertTrue(fin.inFinished);
    assertFalse(fin.outFinished);
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(ErrorCode.CANCEL, rstStream.errorCode);
  }

  @Test public void serverClosesClientInputStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("b", "banana"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("square"), 6);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
    Source source = stream.getSource();
    assertStreamData("square", source);
    connection.ping().roundTripTime(); // Ensure that inFinished has been received.
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertTrue(synStream.inFinished);
    assertFalse(synStream.outFinished);
  }

  @Test public void remoteDoubleSynReply() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(false, 3, headerEntries("b", "banana"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("c", "cola"), false);
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    connection.ping().roundTripTime(); // Ensure that the 2nd SYN REPLY has been received.

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
  }

  @Test public void remoteSendsDataAfterInFinished() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("robot"), 5);
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("c3po"), 4);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    assertStreamData("robot", stream.getSource());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(3, rstStream.streamId);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    assertEquals(2, ping.payload1);
  }

  @Test public void clientDoesNotLimitFlowControl() throws Exception {
    int dataLength = 16384;
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("b", "banana"));
    peer.sendFrame().data(false, 3, new Buffer().write(new byte[dataLength]), dataLength);
    peer.sendFrame().data(false, 3, new Buffer().write(new byte[dataLength]), dataLength);
    peer.sendFrame().data(false, 3, new Buffer().write(new byte[dataLength]), dataLength);
    peer.sendFrame().data(false, 3, new Buffer().write(new byte[dataLength]), dataLength);
    peer.sendFrame().data(false, 3, new Buffer().write(new byte[1]), 1);
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
    assertEquals(headerEntries("b", "banana"), stream.takeResponseHeaders());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    assertEquals(2, ping.payload1);
  }

  @Test public void remoteSendsRefusedStreamBeforeReplyHeaders() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.REFUSED_STREAM);
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
    try {
      stream.takeResponseHeaders();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: REFUSED_STREAM", expected.getMessage());
    }
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    assertEquals(2, ping.payload1);
  }

  @Test public void receiveGoAway() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // SYN_STREAM 3
    peer.acceptFrame(); // PING.
    peer.sendFrame().goAway(3, ErrorCode.PROTOCOL_ERROR, Util.EMPTY_BYTE_ARRAY);
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // DATA STREAM 1
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream1 = connection.newStream(headerEntries("a", "android"), true);
    Http2Stream stream2 = connection.newStream(headerEntries("b", "banana"), true);
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
      connection.newStream(headerEntries("c", "cola"), false);
      fail();
    } catch (ConnectionShutdownException expected) {
    }
    assertTrue(stream1.isOpen());
    assertFalse(stream2.isOpen());
    assertEquals(1, connection.openStreamCount());

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream1.type);
    InFrame synStream2 = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream2.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
    InFrame data1 = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data1.type);
    assertEquals(3, data1.streamId);
    assertTrue(Arrays.equals("abcdef".getBytes("UTF-8"), data1.data));
  }

  @Test public void sendGoAway() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // GOAWAY
    peer.acceptFrame(); // PING
    peer.sendFrame().synStream(false, 2, 0, headerEntries("b", "b")); // Should be ignored!
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.newStream(headerEntries("a", "android"), false);
    Ping ping = connection.ping();
    connection.shutdown(ErrorCode.PROTOCOL_ERROR);
    assertEquals(1, connection.openStreamCount());
    ping.roundTripTime(); // Prevent the peer from exiting prematurely.

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream1.type);
    InFrame pingFrame = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, pingFrame.type);
    InFrame goaway = peer.takeFrame();
    assertEquals(Http2.TYPE_GOAWAY, goaway.type);
    assertEquals(0, goaway.streamId);
    assertEquals(ErrorCode.PROTOCOL_ERROR, goaway.errorCode);
  }

  @Test public void noPingsAfterShutdown() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // GOAWAY
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.shutdown(ErrorCode.INTERNAL_ERROR);
    try {
      connection.ping();
      fail();
    } catch (ConnectionShutdownException expected) {
    }

    // verify the peer received what was expected
    InFrame goaway = peer.takeFrame();
    assertEquals(Http2.TYPE_GOAWAY, goaway.type);
    assertEquals(ErrorCode.INTERNAL_ERROR, goaway.errorCode);
  }

  @Test public void close() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // GOAWAY
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
    assertEquals(1, connection.openStreamCount());
    connection.close();
    assertEquals(0, connection.openStreamCount());
    try {
      connection.newStream(headerEntries("b", "banana"), false);
      fail();
    } catch (ConnectionShutdownException expected) {
    }
    BufferedSink sink = Okio.buffer(stream.getSink());
    try {
      sink.writeByte(0);
      sink.flush();
      fail();
    } catch (IOException expected) {
      assertEquals("stream finished", expected.getMessage());
    }
    try {
      stream.getSource().read(new Buffer(), 1);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: CANCEL", expected.getMessage());
    }

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame goaway = peer.takeFrame();
    assertEquals(Http2.TYPE_GOAWAY, goaway.type);
    InFrame rstStream = peer.takeFrame();
    assertEquals(Http2.TYPE_RST_STREAM, rstStream.type);
    assertEquals(3, rstStream.streamId);
  }

  @Test public void closeCancelsPings() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // PING
    peer.acceptFrame(); // GOAWAY
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Ping ping = connection.ping();
    connection.close();
    assertEquals(-1, ping.roundTripTime());
  }

  @Test public void getResponseHeadersTimesOut() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS);
    long startNanos = System.nanoTime();
    try {
      stream.takeResponseHeaders();
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    awaitWatchdogIdle();
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void readTimesOut() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS);
    Source source = stream.getSource();
    long startNanos = System.nanoTime();
    try {
      source.read(new Buffer(), 1);
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    awaitWatchdogIdle();
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void writeTimesOutAwaitingStreamWindow() throws Exception {
    // Set the peer's receive window to 5 bytes!
    Settings peerSettings = new Settings().set(INITIAL_WINDOW_SIZE, 5);

    // write the mocking script
    peer.sendFrame().settings(peerSettings);
    peer.acceptFrame(); // ACK SETTINGS
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.ping().roundTripTime(); // Make sure settings have been received.
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    Sink sink = stream.getSink();
    sink.write(new Buffer().writeUtf8("abcde"), 5);
    stream.writeTimeout().timeout(500, TimeUnit.MILLISECONDS);
    long startNanos = System.nanoTime();
    sink.write(new Buffer().writeUtf8("f"), 1);
    try {
      sink.flush(); // This will time out waiting on the write window.
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    awaitWatchdogIdle();
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_PING, peer.takeFrame().type);
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_DATA, peer.takeFrame().type);
    assertEquals(Http2.TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void writeTimesOutAwaitingConnectionWindow() throws Exception {
    // Set the peer's receive window to 5 bytes. Give the stream 5 bytes back, so only the
    // connection-level window is applicable.
    Settings peerSettings = new Settings().set(INITIAL_WINDOW_SIZE, 5);

    // write the mocking script
    peer.sendFrame().settings(peerSettings);
    peer.acceptFrame(); // ACK SETTINGS
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().windowUpdate(3, 5);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 3, 0);
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.ping().roundTripTime(); // Make sure settings have been acked.
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    connection.ping().roundTripTime(); // Make sure the window update has been received.
    Sink sink = stream.getSink();
    stream.writeTimeout().timeout(500, TimeUnit.MILLISECONDS);
    sink.write(new Buffer().writeUtf8("abcdef"), 6);
    long startNanos = System.nanoTime();
    try {
      sink.flush(); // This will time out waiting on the write window.
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    awaitWatchdogIdle();
    assertEquals(500d, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 200d /* 200ms delta */);
    assertEquals(0, connection.openStreamCount());

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_PING, peer.takeFrame().type);
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_PING, peer.takeFrame().type);
    assertEquals(Http2.TYPE_DATA, peer.takeFrame().type);
    assertEquals(Http2.TYPE_RST_STREAM, peer.takeFrame().type);
  }

  @Test public void outgoingWritesAreBatched() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);

    // two outgoing writes
    Sink sink = stream.getSink();
    sink.write(new Buffer().writeUtf8("abcde"), 5);
    sink.write(new Buffer().writeUtf8("fghij"), 5);
    sink.close();

    // verify the peer received one incoming frame
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    InFrame data = peer.takeFrame();
    assertEquals(Http2.TYPE_DATA, data.type);
    assertTrue(Arrays.equals("abcdefghij".getBytes("UTF-8"), data.data));
    assertTrue(data.inFinished);
  }

  @Test public void headers() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().headers(3, headerEntries("c", "c3po"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
    assertEquals(Arrays.asList(new Header("a", "android"), null, new Header("c", "c3po")),
        stream.takeResponseHeaders());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame ping = peer.takeFrame();
    assertEquals(Http2.TYPE_PING, ping.type);
  }

  @Test public void readMultipleSetsOfResponseHeaders() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PING
    peer.sendFrame().synReply(true, 3, headerEntries("c", "cola"));
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    stream.getConnection().flush();
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    connection.ping().roundTripTime();
    assertEquals(headerEntries("c", "cola"), stream.takeResponseHeaders());

    // verify the peer received what was expected
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_PING, peer.takeFrame().type);
  }

  @Test public void readSendsWindowUpdate() throws Exception {
    int windowSize = 100;
    int windowUpdateThreshold = 50;

    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
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
    Http2Connection connection = connect(peer);
    connection.okHttpSettings.set(INITIAL_WINDOW_SIZE, windowSize);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(0, stream.unacknowledgedBytesRead);
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    buffer.writeAll(in);
    assertEquals(-1, in.read(buffer, 1));
    assertEquals(150, buffer.size());

    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList<>(2);
      for (int j = 0; j < 2; j++) {
        InFrame windowUpdate = peer.takeFrame();
        assertEquals(Http2.TYPE_WINDOW_UPDATE, windowUpdate.type);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertEquals(windowUpdateThreshold, windowUpdate.windowSizeIncrement);
      }
      assertTrue(windowUpdateStreamIds.contains(0)); // connection
      assertTrue(windowUpdateStreamIds.contains(3)); // stream
    }
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdate() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(-1, client.getSource().read(new Buffer(), 1));

    // Verify the peer received what was expected.
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    assertEquals(5, peer.frameCount());
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdate() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(Util.EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertEquals(Http2.TYPE_HEADERS, peer.takeFrame().type);
    assertEquals(Http2.TYPE_DATA, peer.takeFrame().type);
    assertEquals(5, peer.frameCount());
  }

  @Test public void testTruncatedDataFrame() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(false, 3, data(1024), 1024);
    peer.truncateLastFrame(8 + 100);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertEquals(headerEntries("a", "android"), stream.takeResponseHeaders());
    Source in = stream.getSource();
    try {
      Okio.buffer(in).readByteString(101);
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }
  }

  @Test public void blockedStreamDoesntStarveNewStream() throws Exception {
    int framesThatFillWindow = roundUp(DEFAULT_INITIAL_WINDOW_SIZE, peer.maxOutboundDataLength());

    // Write the mocking script. This accepts more data frames than necessary!
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // SETTINGS ACK
    peer.acceptFrame(); // SYN_STREAM on stream 1
    for (int i = 0; i < framesThatFillWindow; i++) {
      peer.acceptFrame(); // DATA on stream 1
    }
    peer.acceptFrame(); // SYN_STREAM on stream 2
    peer.acceptFrame(); // DATA on stream 2
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream stream1 = connection.newStream(headerEntries("a", "apple"), true);
    BufferedSink out1 = Okio.buffer(stream1.getSink());
    out1.write(new byte[DEFAULT_INITIAL_WINDOW_SIZE]);
    out1.flush();

    // Check that we've filled the window for both the stream and also the connection.
    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(3).bytesLeftInWriteWindow);

    // receiving a window update on the connection will unblock new streams.
    connection.readerRunnable.windowUpdate(0, 3);

    assertEquals(3, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(3).bytesLeftInWriteWindow);

    // Another stream should be able to send data even though 1 is blocked.
    Http2Stream stream2 = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out2 = Okio.buffer(stream2.getSink());
    out2.writeUtf8("foo");
    out2.flush();

    assertEquals(0, connection.bytesLeftInWriteWindow);
    assertEquals(0, connection.getStream(3).bytesLeftInWriteWindow);
    assertEquals(DEFAULT_INITIAL_WINDOW_SIZE - 3, connection.getStream(5).bytesLeftInWriteWindow);
  }

  @Test public void remoteOmitsInitialSettings() throws Exception {
    // Write the mocking script. Note no SETTINGS frame is sent or acknowledged.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().synReply(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // GOAWAY
    peer.play();

    Http2Connection connection = new Http2Connection.Builder(true)
        .socket(peer.openSocket())
        .build();
    connection.start(false);

    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    try {
      stream.takeResponseHeaders();
      fail();
    } catch (IOException expected) {
      assertEquals("stream was reset: PROTOCOL_ERROR", expected.getMessage());
    }

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertEquals(Http2.TYPE_HEADERS, synStream.type);
    InFrame goaway = peer.takeFrame();
    assertEquals(Http2.TYPE_GOAWAY, goaway.type);
    assertEquals(ErrorCode.PROTOCOL_ERROR, goaway.errorCode);
  }

  private Buffer data(int byteCount) {
    return new Buffer().write(new byte[byteCount]);
  }

  private void assertStreamData(String expected, Source source) throws IOException {
    String actual = Okio.buffer(source).readUtf8();
    assertEquals(expected, actual);
  }

  /**
   * Returns true when all work currently in progress by the watchdog have completed. This method
   * creates more work for the watchdog and waits for that work to be executed. When it is, we know
   * work that preceded this call is complete.
   */
  private void awaitWatchdogIdle() throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    AsyncTimeout watchdogJob = new AsyncTimeout() {
      @Override protected void timedOut() {
        latch.countDown();
      }
    };
    watchdogJob.deadlineNanoTime(System.nanoTime()); // Due immediately!
    watchdogJob.enter();
    latch.await();
  }

  static int roundUp(int num, int divisor) {
    return (num + divisor - 1) / divisor;
  }

  private Http2Connection connectWithSettings(boolean client, Settings settings) throws Exception {
    peer.setClient(client);
    peer.sendFrame().settings(settings);
    peer.acceptFrame(); // ACK
    peer.play();
    return connect(peer);
  }

  private Http2Connection connect(MockHttp2Peer peer) throws Exception {
    return connect(peer, IGNORE, REFUSE_INCOMING_STREAMS);
  }

  /** Builds a new connection to {@code peer} with settings acked. */
  private Http2Connection connect(MockHttp2Peer peer, PushObserver pushObserver,
      Http2Connection.Listener listener) throws Exception {
    Http2Connection connection = new Http2Connection.Builder(true)
        .socket(peer.openSocket())
        .pushObserver(pushObserver)
        .listener(listener)
        .build();
    connection.start(false);

    // verify the peer received the ACK
    InFrame ackFrame = peer.takeFrame();
    assertEquals(Http2.TYPE_SETTINGS, ackFrame.type);
    assertEquals(0, ackFrame.streamId);
    assertTrue(ackFrame.ack);

    return connection;
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
