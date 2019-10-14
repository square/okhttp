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

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Headers;
import okhttp3.internal.Util;
import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.internal.http2.MockHttp2Peer.InFrame;
import okio.AsyncTimeout;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Utf8;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static okhttp3.TestUtil.headerEntries;
import static okhttp3.TestUtil.repeat;
import static okhttp3.internal.Util.EMPTY_BYTE_ARRAY;
import static okhttp3.internal.Util.EMPTY_HEADERS;
import static okhttp3.internal.http2.Http2Connection.Listener.REFUSE_INCOMING_STREAMS;
import static okhttp3.internal.http2.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static okhttp3.internal.http2.Settings.ENABLE_PUSH;
import static okhttp3.internal.http2.Settings.HEADER_TABLE_SIZE;
import static okhttp3.internal.http2.Settings.INITIAL_WINDOW_SIZE;
import static okhttp3.internal.http2.Settings.MAX_CONCURRENT_STREAMS;
import static okhttp3.internal.http2.Settings.MAX_FRAME_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

public final class Http2ConnectionTest {
  private final MockHttp2Peer peer = new MockHttp2Peer();

  @Rule public final TestRule timeout = new Timeout(5_000, TimeUnit.MILLISECONDS);

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
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    assertThat(ping.streamId).isEqualTo(0);
    assertThat(ping.payload1).isEqualTo(2);
    assertThat(ping.payload2).isEqualTo(3);
    assertThat(ping.ack).isTrue();
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
    assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS);
    assertThat(ackFrame.streamId).isEqualTo(0);
    assertThat(ackFrame.ack).isTrue();

    // This stream was created *after* the connection settings were adjusted.
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);

    assertThat(connection.getPeerSettings().getInitialWindowSize()).isEqualTo(3368L);
    // New Stream is has the most recent initial window size.
    assertThat(stream.getWriteBytesTotal()).isEqualTo(0L);
    assertThat(stream.getWriteBytesMaximum()).isEqualTo(3368L);
  }

  @Test public void peerHttp2ServerZerosCompressionTable() throws Exception {
    boolean client = false; // Peer is server, so we are client.
    Settings settings = new Settings();
    settings.set(HEADER_TABLE_SIZE, 0);

    Http2Connection connection = connectWithSettings(client, settings);

    // Verify the peer's settings were read and applied.
    assertThat(connection.getPeerSettings().getHeaderTableSize()).isEqualTo(0);
    Http2Writer writer = connection.getWriter();
    assertThat(writer.getHpackWriter().dynamicTableByteCount).isEqualTo(0);
    assertThat(writer.getHpackWriter().headerTableSizeSetting).isEqualTo(0);
  }

  @Test public void peerHttp2ClientDisablesPush() throws Exception {
    boolean client = false; // Peer is client, so we are server.
    Settings settings = new Settings();
    settings.set(ENABLE_PUSH, 0); // The peer client disables push.

    Http2Connection connection = connectWithSettings(client, settings);

    // verify the peer's settings were read and applied.
    assertThat(connection.getPeerSettings().getEnablePush(true)).isFalse();
  }

  @Test public void peerIncreasesMaxFrameSize() throws Exception {
    int newMaxFrameSize = 0x4001;
    Settings settings = new Settings();
    settings.set(MAX_FRAME_SIZE, newMaxFrameSize);

    Http2Connection connection = connectWithSettings(true, settings);

    // verify the peer's settings were read and applied.
    assertThat(connection.getPeerSettings().getMaxFrameSize(-1)).isEqualTo(newMaxFrameSize);
    assertThat(connection.getWriter().maxDataLength()).isEqualTo(newMaxFrameSize);
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
    connection.writePingAndAwaitPong(); // Ensure the SETTINGS have been received.
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
    BufferedSink sink = Okio.buffer(stream.getSink());
    sink.writeUtf8("abcdefghi");
    sink.flush();

    // Verify the peer received what was expected.
    peer.takeFrame(); // PING
    InFrame headers = peer.takeFrame();
    assertThat(headers.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame data1 = peer.takeFrame();
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(data1.streamId).isEqualTo(3);
    assertArrayEquals("abcde".getBytes(UTF_8), data1.data);
    InFrame data2 = peer.takeFrame();
    assertThat(data2.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(data2.streamId).isEqualTo(3);
    assertArrayEquals("fghi".getBytes(UTF_8), data2.data);
  }

  /**
   * Confirm that we account for discarded data frames. It's possible that data frames are in-flight
   * just prior to us canceling a stream.
   */
  @Test public void discardedDataFramesAreCounted() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM 3
    peer.sendFrame().headers(false, 3, headerEntries("a", "apple"));
    peer.sendFrame().data(false, 3, data(1024), 1024);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().data(true, 3, data(1024), 1024);
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    Http2Connection connection = connect(peer);
    Http2Stream stream1 = connection.newStream(headerEntries("b", "bark"), false);
    Source source = stream1.getSource();
    Buffer buffer = new Buffer();
    while (buffer.size() != 1024) source.read(buffer, 1024);
    stream1.close(ErrorCode.CANCEL, null);

    InFrame frame1 = peer.takeFrame();
    assertThat(frame1.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame frame2 = peer.takeFrame();
    assertThat(frame2.type).isEqualTo(Http2.TYPE_RST_STREAM);
    InFrame frame3 = peer.takeFrame();
    assertThat(frame3.type).isEqualTo(Http2.TYPE_RST_STREAM);

    assertThat(connection.getReadBytesAcknowledged()).isEqualTo(0L);
    assertThat(connection.getReadBytesTotal()).isEqualTo(2048L);
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
    connection.writePingAndAwaitPong(); // Ensure the GO_AWAY that resets stream2 has been received.
    BufferedSink sink1 = Okio.buffer(stream1.getSink());
    BufferedSink sink2 = Okio.buffer(stream2.getSink());
    sink1.writeUtf8("abc");
    try {
      sink2.writeUtf8("abc");
      sink2.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: REFUSED_STREAM");
    }
    sink1.writeUtf8("def");
    sink1.close();
    try {
      connection.newStream(headerEntries("c", "cola"), true);
      fail();
    } catch (ConnectionShutdownException expected) {
    }
    assertThat(stream1.isOpen()).isTrue();
    assertThat(stream2.isOpen()).isFalse();
    assertThat(connection.openStreamCount()).isEqualTo(1);

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame synStream2 = peer.takeFrame();
    assertThat(synStream2.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    InFrame data1 = peer.takeFrame();
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(data1.streamId).isEqualTo(3);
    assertArrayEquals("abcdef".getBytes(UTF_8), data1.data);
  }

  @Test public void readSendsWindowUpdateHttp2() throws Exception {
    int windowSize = 100;
    int windowUpdateThreshold = 50;

    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    connection.getOkHttpSettings().set(INITIAL_WINDOW_SIZE, windowSize);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(stream.getReadBytesAcknowledged()).isEqualTo(0L);
    assertThat(stream.getReadBytesTotal()).isEqualTo(0L);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    buffer.writeAll(in);
    assertThat(in.read(buffer, 1)).isEqualTo(-1);
    assertThat(buffer.size()).isEqualTo(150);

    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList<>(2);
      for (int j = 0; j < 2; j++) {
        InFrame windowUpdate = peer.takeFrame();
        assertThat(windowUpdate.type).isEqualTo(Http2.TYPE_WINDOW_UPDATE);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertThat(windowUpdate.windowSizeIncrement).isEqualTo(windowUpdateThreshold);
      }
      // connection
      assertThat(windowUpdateStreamIds).contains(0);
      // stream
      assertThat(windowUpdateStreamIds).contains(3);
    }
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdateHttp2() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(client.getSource().read(new Buffer(), 1)).isEqualTo(-1);

    // Verify the peer received what was expected.
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.frameCount()).isEqualTo(5);
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdateHttp2() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA);
    assertThat(peer.frameCount()).isEqualTo(5);
  }

  @Test public void maxFrameSizeHonored() throws Exception {
    byte[] buff = new byte[peer.maxOutboundDataLength() + 1];
    Arrays.fill(buff, (byte) '*');

    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame data = peer.takeFrame();
    assertThat(data.data.length).isEqualTo(peer.maxOutboundDataLength());
    data = peer.takeFrame();
    assertThat(data.data.length).isEqualTo(1);
  }

  @Test public void pushPromiseStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    final List<Header> expectedRequestHeaders = asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/cached")
    );
    peer.sendFrame().pushPromise(3, 2, expectedRequestHeaders);
    final List<Header> expectedResponseHeaders = asList(
        new Header(Header.RESPONSE_STATUS, "200")
    );
    peer.sendFrame().headers(true, 2, expectedResponseHeaders);
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    RecordingPushObserver observer = new RecordingPushObserver();

    // play it back
    Http2Connection connection = connect(peer, observer, REFUSE_INCOMING_STREAMS);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(client.getSource().read(new Buffer(), 1)).isEqualTo(-1);

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);

    assertThat(observer.takeEvent()).isEqualTo(expectedRequestHeaders);
    assertThat(observer.takeEvent()).isEqualTo(expectedResponseHeaders);
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
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR);
  }

  @Test public void pushPromiseStreamsAutomaticallyCancel() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().pushPromise(3, 2, asList(
        new Header(Header.TARGET_METHOD, "GET"),
        new Header(Header.TARGET_SCHEME, "https"),
        new Header(Header.TARGET_AUTHORITY, "squareup.com"),
        new Header(Header.TARGET_PATH, "/cached")
    ));
    peer.sendFrame().headers(true, 2, asList(
        new Header(Header.RESPONSE_STATUS, "200")
    ));
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    connect(peer, PushObserver.CANCEL, REFUSE_INCOMING_STREAMS);

    // verify the peer received what was expected
    InFrame rstStream = peer.takeFrame();
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.streamId).isEqualTo(2);
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL);
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
    Http2Connection connection = new Http2Connection.Builder(true, TaskRunner.INSTANCE)
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
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    assertStreamData("robot", stream.getSource());
    connection.writePingAndAwaitPong();
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.outFinished).isFalse();
    assertThat(synStream.streamId).isEqualTo(3);
    assertThat(synStream.associatedStreamId).isEqualTo(-1);
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("b", "banana"));
    InFrame requestData = peer.takeFrame();
    assertArrayEquals("c3po".getBytes(UTF_8), requestData.data);
  }

  @Test public void serverFinishesStreamWithHeaders() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(true, 3, headerEntries("headers", "bam"));
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "artichaut"), false);
    connection.writePingAndAwaitPong();
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("headers", "bam"));
    assertThat(stream.trailers()).isEqualTo(EMPTY_HEADERS);
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.outFinished).isFalse();
    assertThat(synStream.streamId).isEqualTo(3);
    assertThat(synStream.associatedStreamId).isEqualTo(-1);
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"));
  }

  @Test public void serverWritesTrailersAndClientReadsTrailers() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("headers", "bam"));
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(true, 3, headerEntries("trailers", "boom"));
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "artichaut"), false);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("headers", "bam"));
    connection.writePingAndAwaitPong();
    assertThat(stream.trailers()).isEqualTo(Headers.of("trailers", "boom"));
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.outFinished).isFalse();
    assertThat(synStream.streamId).isEqualTo(3);
    assertThat(synStream.associatedStreamId).isEqualTo(-1);
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"));
  }

  @Test public void serverWritesTrailersWithData() throws Exception {
    // We buffer some outbound data and headers and confirm that the END_STREAM flag comes with the
    // headers (and not with the data).

    // write the mocking script for the client
    peer.setClient(true);

    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.sendFrame().headers(true, 3, headerEntries("client", "abc"));
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // HEADERS STREAM 3
    peer.acceptFrame(); // DATA STREAM 3 "abcde"
    peer.acceptFrame(); // HEADERS STREAM 3
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
    stream.enqueueTrailers(Headers.of("foo", "bar"));
    BufferedSink sink = Okio.buffer(stream.getSink());
    sink.writeUtf8("abcdefghi");
    sink.close();

    // Verify the peer received what was expected.
    InFrame headers1 = peer.takeFrame();
    assertThat(headers1.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame data1 = peer.takeFrame();
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(data1.streamId).isEqualTo(3);
    assertArrayEquals("abcdefghi".getBytes(UTF_8), data1.data);
    assertThat(data1.inFinished).isFalse();
    InFrame headers2 = peer.takeFrame();
    assertThat(headers2.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(headers2.inFinished).isTrue();
  }

  @Test public void clientCannotReadTrailersWithoutExhaustingStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().data(false, 3, new Buffer().writeUtf8("robot"), 5);
    peer.sendFrame().headers(true, 3, headerEntries("trailers", "boom"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "artichaut"), true);
    connection.writePingAndAwaitPong();
    try {
      stream.trailers();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void clientCannotReadTrailersIfTheStreamFailed() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.PROTOCOL_ERROR);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "artichaut"), true);
    connection.writePingAndAwaitPong();
    try {
      stream.trailers();
      fail();
    } catch (StreamResetException expected) {
    }
  }

  @Test public void serverCannotEnqueueTrailersAfterFinishingTheStream() throws Exception {
    peer.setClient(true);
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    connection.writePingAndAwaitPong();
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), true);
    // finish the stream
    stream.writeHeaders(headerEntries("b", "berserk"), true, false);
    try {
      stream.enqueueTrailers(Headers.of("trailers", "boom"));
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void noTrailersFrameYieldsEmptyTrailers() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("headers", "bam"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("robot"), 5);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "artichaut"), false);
    BufferedSource source = Okio.buffer(stream.getSource());
    connection.writePingAndAwaitPong();
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("headers", "bam"));
    assertThat(source.readUtf8(5)).isEqualTo("robot");
    assertThat(stream.trailers()).isEqualTo(EMPTY_HEADERS);
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.outFinished).isFalse();
    assertThat(synStream.streamId).isEqualTo(3);
    assertThat(synStream.associatedStreamId).isEqualTo(-1);
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"));
  }

  @Test public void serverReadsHeadersDataHeaders() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // HEADERS
    peer.sendFrame().headers(true, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("c3po");
    out.close();
    stream.writeHeaders(headerEntries("e", "elephant"), false, false);
    connection.writePingAndAwaitPong();
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.outFinished).isFalse();
    assertThat(synStream.streamId).isEqualTo(3);
    assertThat(synStream.associatedStreamId).isEqualTo(-1);
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("b", "banana"));
    InFrame requestData = peer.takeFrame();
    assertArrayEquals("c3po".getBytes(UTF_8), requestData.data);

    InFrame nextFrame = peer.takeFrame();
    assertThat(nextFrame.headerBlock).isEqualTo(headerEntries("e", "elephant"));
  }

  @Test public void clientCreatesStreamAndServerRepliesWithFin() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(true, 3, headerEntries("a", "android"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.newStream(headerEntries("b", "banana"), false);
    assertThat(connection.openStreamCount()).isEqualTo(1);
    connection.writePingAndAwaitPong(); // Ensure that the SYN_REPLY has been received.
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
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
    assertThat(ping.streamId).isEqualTo(0);
    assertThat(ping.payload1).isEqualTo(2);
    assertThat(ping.payload2).isEqualTo(0);
    assertThat(ping.ack).isTrue();
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
    long pingAtNanos = System.nanoTime();
    connection.writePingAndAwaitPong();
    long elapsedNanos = System.nanoTime() - pingAtNanos;
    assertThat(elapsedNanos).isGreaterThan(0L);
    assertThat(elapsedNanos).isLessThan(TimeUnit.SECONDS.toNanos(1));

    // verify the peer received what was expected
    InFrame pingFrame = peer.takeFrame();
    assertThat(pingFrame.type).isEqualTo(Http2.TYPE_PING);
    assertThat(pingFrame.streamId).isEqualTo(0);
    // OkOk
    assertThat(pingFrame.payload1).isEqualTo(0x4f4b6f6b);
    // donut
    assertThat(pingFrame.payload2).isEqualTo(0xf09f8da9);
    assertThat(pingFrame.ack).isFalse();
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
    assertThat(ping2.payload1).isEqualTo(2);
    InFrame ping4 = peer.takeFrame();
    assertThat(ping4.payload1).isEqualTo(4);
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

      @Override public void onSettings(Http2Connection connection, Settings settings) {
        maxConcurrentStreams.set(settings.getMaxConcurrentStreams());
        maxConcurrentStreamsUpdated.countDown();
      }
    };
    Http2Connection connection = connect(peer, IGNORE, listener);

    synchronized (connection) {
      assertThat(connection.getPeerSettings().getMaxConcurrentStreams()).isEqualTo(10);
    }
    maxConcurrentStreamsUpdated.await();
    assertThat(maxConcurrentStreams.get()).isEqualTo(10);
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

    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_SETTINGS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING);
    synchronized (connection) {
      assertThat(connection.getPeerSettings().getHeaderTableSize()).isEqualTo(10000);
      assertThat(connection.getPeerSettings().getInitialWindowSize()).isEqualTo(40000);
      assertThat(connection.getPeerSettings().getMaxFrameSize(-1)).isEqualTo(50000);
      assertThat(connection.getPeerSettings().getMaxConcurrentStreams()).isEqualTo(60000);
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
    connection.getReaderRunnable().applyAndAckSettings(true, settings2);

    synchronized (connection) {
      assertThat(connection.getPeerSettings().getHeaderTableSize()).isEqualTo(-1);
      assertThat(connection.getPeerSettings().getInitialWindowSize()).isEqualTo(
          (long) DEFAULT_INITIAL_WINDOW_SIZE);
      assertThat(connection.getPeerSettings().getMaxFrameSize(-1)).isEqualTo(-1);
      assertThat(connection.getPeerSettings().getMaxConcurrentStreams()).isEqualTo(60000);
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
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.streamId).isEqualTo(41);
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR);
    InFrame ping = peer.takeFrame();
    assertThat(ping.payload1).isEqualTo(2);
  }

  @Test public void bogusReplySilentlyIgnored() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.sendFrame().headers(false, 41, headerEntries("a", "android"));
    peer.sendFrame().ping(false, 2, 0);
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    connect(peer);

    // verify the peer received what was expected
    InFrame ping = peer.takeFrame();
    assertThat(ping.payload1).isEqualTo(2);
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
    connection.writePingAndAwaitPong(); // Ensure that the RST_CANCEL has been received.
    try {
      out.writeUtf8("square");
      out.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: CANCEL");
    }
    try {
      out.close();
      fail();
    } catch (IOException expected) {
      // Close throws because buffered data wasn't flushed.
    }
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.inFinished).isFalse();
    assertThat(synStream.outFinished).isFalse();
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
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
      assertThat(expected.getMessage()).isEqualTo("stream closed");
    }
    try {
      out.writeUtf8("a");
      out.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream finished");
    }
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.inFinished).isTrue();
    assertThat(synStream.outFinished).isFalse();
    InFrame rstStream = peer.takeFrame();
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL);
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
      assertThat(expected.getMessage()).isEqualTo("stream closed");
    }
    out.writeUtf8("square");
    out.flush();
    out.close();
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.inFinished).isFalse();
    assertThat(synStream.outFinished).isFalse();
    InFrame data = peer.takeFrame();
    assertThat(data.type).isEqualTo(Http2.TYPE_DATA);
    assertArrayEquals("square".getBytes(UTF_8), data.data);
    InFrame fin = peer.takeFrame();
    assertThat(fin.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(fin.inFinished).isTrue();
    assertThat(fin.outFinished).isFalse();
    InFrame rstStream = peer.takeFrame();
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL);
  }

  @Test public void serverClosesClientInputStream() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("square"), 6);
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("a", "android"), false);
    Source source = stream.getSource();
    assertStreamData("square", source);
    connection.writePingAndAwaitPong(); // Ensure that inFinished has been received.
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(synStream.inFinished).isTrue();
    assertThat(synStream.outFinished).isFalse();
  }

  @Test public void remoteDoubleSynReply() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("c", "cola"), false);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    connection.writePingAndAwaitPong(); // Ensure that the 2nd SYN REPLY has been received.

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
  }

  @Test public void remoteSendsDataAfterInFinished() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("robot"), 5);
    peer.sendFrame().data(true, 3, new Buffer().writeUtf8("c3po"), 4);
    peer.acceptFrame(); // RST_STREAM
    peer.sendFrame().ping(false, 2, 0); // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame(); // PING
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    assertStreamData("robot", stream.getSource());

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame rstStream = peer.takeFrame();
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.streamId).isEqualTo(3);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    assertThat(ping.payload1).isEqualTo(2);
  }

  @Test public void clientDoesNotLimitFlowControl() throws Exception {
    int dataLength = 16384;
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"));
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
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("b", "banana"));

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    assertThat(ping.payload1).isEqualTo(2);
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
      stream.takeHeaders();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: REFUSED_STREAM");
    }
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    assertThat(ping.payload1).isEqualTo(2);
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
    connection.writePingAndAwaitPong(); // Ensure the GO_AWAY that resets stream2 has been received.
    BufferedSink sink1 = Okio.buffer(stream1.getSink());
    BufferedSink sink2 = Okio.buffer(stream2.getSink());
    sink1.writeUtf8("abc");
    try {
      sink2.writeUtf8("abc");
      sink2.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: REFUSED_STREAM");
    }
    sink1.writeUtf8("def");
    sink1.close();
    try {
      connection.newStream(headerEntries("c", "cola"), false);
      fail();
    } catch (ConnectionShutdownException expected) {
    }
    assertThat(stream1.isOpen()).isTrue();
    assertThat(stream2.isOpen()).isFalse();
    assertThat(connection.openStreamCount()).isEqualTo(1);

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame synStream2 = peer.takeFrame();
    assertThat(synStream2.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
    InFrame data1 = peer.takeFrame();
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA);
    assertThat(data1.streamId).isEqualTo(3);
    assertArrayEquals("abcdef".getBytes(UTF_8), data1.data);
  }

  @Test public void sendGoAway() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM 1
    peer.acceptFrame(); // GOAWAY
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(false, 2, headerEntries("b", "b")); // Should be ignored!
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.newStream(headerEntries("a", "android"), false);
    synchronized (connection) {
      if (connection.isShutdown()) {
        throw new ConnectionShutdownException();
      }
    }
    connection.writePing(false, 0x01, 0x02);
    connection.shutdown(ErrorCode.PROTOCOL_ERROR);
    assertThat(connection.openStreamCount()).isEqualTo(1);
    connection.awaitPong(); // Prevent the peer from exiting prematurely.

    // verify the peer received what was expected
    InFrame synStream1 = peer.takeFrame();
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame pingFrame = peer.takeFrame();
    assertThat(pingFrame.type).isEqualTo(Http2.TYPE_PING);
    InFrame goaway = peer.takeFrame();
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY);
    assertThat(goaway.streamId).isEqualTo(0);
    assertThat(goaway.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR);
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
    assertThat(connection.openStreamCount()).isEqualTo(1);
    connection.close();
    assertThat(connection.openStreamCount()).isEqualTo(0);
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
      assertThat(expected.getMessage()).isEqualTo("stream finished");
    }
    try {
      stream.getSource().read(new Buffer(), 1);
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: CANCEL");
    }

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame goaway = peer.takeFrame();
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY);
    InFrame rstStream = peer.takeFrame();
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM);
    assertThat(rstStream.streamId).isEqualTo(3);
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
      stream.takeHeaders();
      fail();
    } catch (InterruptedIOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    awaitWatchdogIdle();
    /* 200ms delta */
    assertThat((double) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(500d, offset(200d));
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM);
  }

  @Test public void readTimesOut() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    /* 200ms delta */
    assertThat((double) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(500d, offset(200d));
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM);
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
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.writePingAndAwaitPong(); // Make sure settings have been received.
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
    /* 200ms delta */
    assertThat((double) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(500d, offset(200d));
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM);
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
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().ping(true, 3, 0);
    peer.acceptFrame(); // DATA
    peer.acceptFrame(); // RST_STREAM
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    connection.writePingAndAwaitPong(); // Make sure settings have been acked.
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    connection.writePingAndAwaitPong(); // Make sure the window update has been received.
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
    /* 200ms delta */
    assertThat((double) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(500d, offset(200d));
    assertThat(connection.openStreamCount()).isEqualTo(0);

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM);
  }

  @Test public void outgoingWritesAreBatched() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame data = peer.takeFrame();
    assertThat(data.type).isEqualTo(Http2.TYPE_DATA);
    assertArrayEquals("abcdefghij".getBytes(UTF_8), data.data);
    assertThat(data.inFinished).isTrue();
  }

  @Test public void headers() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.sendFrame().headers(false, 3, headerEntries("c", "c3po"));
    peer.sendFrame().ping(true, 1, 0);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    connection.writePingAndAwaitPong(); // Ensure that the HEADERS has been received.
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("c", "c3po"));

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame ping = peer.takeFrame();
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING);
  }

  @Test public void readMultipleSetsOfResponseHeaders() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // PING
    peer.sendFrame().headers(true, 3, headerEntries("c", "cola"));
    peer.sendFrame().ping(true, 1, 0); // PONG
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), true);
    stream.getConnection().flush();
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    connection.writePingAndAwaitPong();
    assertThat(stream.trailers()).isEqualTo(Headers.of("c", "cola"));

    // verify the peer received what was expected
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING);
  }

  @Test public void readSendsWindowUpdate() throws Exception {
    int windowSize = 100;
    int windowUpdateThreshold = 50;

    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
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
    connection.getOkHttpSettings().set(INITIAL_WINDOW_SIZE, windowSize);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(stream.getReadBytesAcknowledged()).isEqualTo(0L);
    assertThat(stream.getReadBytesTotal()).isEqualTo(0L);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    Source in = stream.getSource();
    Buffer buffer = new Buffer();
    buffer.writeAll(in);
    assertThat(in.read(buffer, 1)).isEqualTo(-1);
    assertThat(buffer.size()).isEqualTo(150);

    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    for (int i = 0; i < 3; i++) {
      List<Integer> windowUpdateStreamIds = new ArrayList<>(2);
      for (int j = 0; j < 2; j++) {
        InFrame windowUpdate = peer.takeFrame();
        assertThat(windowUpdate.type).isEqualTo(Http2.TYPE_WINDOW_UPDATE);
        windowUpdateStreamIds.add(windowUpdate.streamId);
        assertThat(windowUpdate.windowSizeIncrement).isEqualTo(windowUpdateThreshold);
      }
      // connection
      assertThat(windowUpdateStreamIds).contains(0);
      // stream
      assertThat(windowUpdateStreamIds).contains(3);
    }
  }

  @Test public void serverSendsEmptyDataClientDoesntSendWindowUpdate() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(true, 3, data(0), 0);
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(client.getSource().read(new Buffer(), 1)).isEqualTo(-1);

    // Verify the peer received what was expected.
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.frameCount()).isEqualTo(5);
  }

  @Test public void clientSendsEmptyDataServerDoesntSendWindowUpdate() throws Exception {
    // Write the mocking script.
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.acceptFrame(); // DATA
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.play();

    // Play it back.
    Http2Connection connection = connect(peer);
    Http2Stream client = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out = Okio.buffer(client.getSink());
    out.write(Util.EMPTY_BYTE_ARRAY);
    out.flush();
    out.close();

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS);
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA);
    assertThat(peer.frameCount()).isEqualTo(5);
  }

  @Test public void testTruncatedDataFrame() throws Exception {
    // write the mocking script
    peer.sendFrame().settings(new Settings());
    peer.acceptFrame(); // ACK
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.sendFrame().data(false, 3, data(1024), 1024);
    peer.truncateLastFrame(8 + 100);
    peer.play();

    // play it back
    Http2Connection connection = connect(peer);
    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    assertThat(stream.takeHeaders()).isEqualTo(Headers.of("a", "android"));
    Source in = stream.getSource();
    try {
      Okio.buffer(in).readByteString(101);
      fail();
    } catch (EOFException expected) {
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
    assertThat(connection.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(connection.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(stream1.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(stream1.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);

    // receiving a window update on the connection will unblock new streams.
    connection.getReaderRunnable().windowUpdate(0, 3);

    assertThat(connection.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(connection.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE + 3);
    assertThat(stream1.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(stream1.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);

    // Another stream should be able to send data even though 1 is blocked.
    Http2Stream stream2 = connection.newStream(headerEntries("b", "banana"), true);
    BufferedSink out2 = Okio.buffer(stream2.getSink());
    out2.writeUtf8("foo");
    out2.flush();

    assertThat(connection.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE + 3);
    assertThat(connection.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE + 3);
    assertThat(stream1.getWriteBytesTotal()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(stream1.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
    assertThat(stream2.getWriteBytesTotal()).isEqualTo(3L);
    assertThat(stream2.getWriteBytesMaximum()).isEqualTo(DEFAULT_INITIAL_WINDOW_SIZE);
  }

  @Test public void remoteOmitsInitialSettings() throws Exception {
    // Write the mocking script. Note no SETTINGS frame is sent or acknowledged.
    peer.acceptFrame(); // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"));
    peer.acceptFrame(); // GOAWAY
    peer.play();

    Http2Connection connection = new Http2Connection.Builder(true, TaskRunner.INSTANCE)
        .socket(peer.openSocket())
        .build();
    connection.start(false);

    Http2Stream stream = connection.newStream(headerEntries("b", "banana"), false);
    try {
      stream.takeHeaders();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("Expected a SETTINGS frame but was 1");
    }

    // verify the peer received what was expected
    InFrame synStream = peer.takeFrame();
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS);
    InFrame goaway = peer.takeFrame();
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY);
    assertThat(goaway.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR);
  }

  private Buffer data(int byteCount) {
    return new Buffer().write(new byte[byteCount]);
  }

  private void assertStreamData(String expected, Source source) throws IOException {
    String actual = Okio.buffer(source).readUtf8();
    assertThat(actual).isEqualTo(expected);
  }

  /**
   * Returns true when all work currently in progress by the watchdog have completed. This method
   * creates more work for the watchdog and waits for that work to be executed. When it is, we know
   * work that preceded this call is complete.
   */
  private void awaitWatchdogIdle() throws Exception {
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
    return connect(peer, IGNORE, Http2Connection.Listener.REFUSE_INCOMING_STREAMS);
  }

  /** Builds a new connection to {@code peer} with settings acked. */
  private Http2Connection connect(MockHttp2Peer peer, PushObserver pushObserver,
      Http2Connection.Listener listener) throws Exception {
    Http2Connection connection = new Http2Connection.Builder(true, TaskRunner.INSTANCE)
        .socket(peer.openSocket())
        .pushObserver(pushObserver)
        .listener(listener)
        .build();
    connection.start(false);

    // verify the peer received the ACK
    InFrame ackFrame = peer.takeFrame();
    assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS);
    assertThat(ackFrame.streamId).isEqualTo(0);
    assertThat(ackFrame.ack).isTrue();

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

    public synchronized Object takeEvent() throws Exception {
      while (events.isEmpty()) {
        wait();
      }
      return events.remove(0);
    }

    @Override public synchronized boolean onRequest(int streamId, List<Header> requestHeaders) {
      assertThat(streamId).isEqualTo(2);
      events.add(requestHeaders);
      notifyAll();
      return false;
    }

    @Override public synchronized boolean onHeaders(
        int streamId, List<Header> responseHeaders, boolean last) {
      assertThat(streamId).isEqualTo(2);
      assertThat(last).isTrue();
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
