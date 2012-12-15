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

package com.squareup.okhttp.internal.net.spdy;

import static com.squareup.okhttp.internal.net.spdy.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.FLAG_FIN;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_DATA;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_NOOP;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_PING;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_SYN_REPLY;
import static com.squareup.okhttp.internal.net.spdy.SpdyConnection.TYPE_SYN_STREAM;
import static com.squareup.okhttp.internal.net.spdy.SpdyStream.RST_INVALID_STREAM;
import static com.squareup.okhttp.internal.util.Charsets.UTF_8;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;

public final class SpdyConnectionTest extends TestCase {
    private static final IncomingStreamHandler REJECT_INCOMING_STREAMS
            = new IncomingStreamHandler() {
        @Override public void receive(SpdyStream stream) throws IOException {
            throw new AssertionError();
        }
    };
    private final MockSpdyPeer peer = new MockSpdyPeer();

    public void testClientCreatesStreamAndServerReplies() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().synReply(0, 1, Arrays.asList("a", "android"));
        peer.sendFrame().data(SpdyConnection.FLAG_FIN, 1, "robot".getBytes("UTF-8"));
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
        assertStreamData("robot", stream.getInputStream());
        writeAndClose(stream, "c3po");

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(0, synStream.flags);
        assertEquals(1, synStream.streamId);
        assertEquals(0, synStream.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), synStream.nameValueBlock);
        MockSpdyPeer.InFrame requestData = peer.takeFrame();
        assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
    }

    public void testServerCreatesStreamAndClientReplies() throws Exception {
        // write the mocking script
        peer.sendFrame().synStream(0, 2, 0, 0, Arrays.asList("a", "android"));
        peer.acceptFrame();
        peer.play();

        // play it back
        final AtomicInteger receiveCount = new AtomicInteger();
        IncomingStreamHandler handler = new IncomingStreamHandler() {
            @Override public void receive(SpdyStream stream) throws IOException {
                receiveCount.incrementAndGet();
                assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
                assertEquals(-1, stream.getRstStatusCode());
                stream.reply(Arrays.asList("b", "banana"), true);

            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.type);
        assertEquals(0, reply.flags);
        assertEquals(2, reply.streamId);
        assertEquals(Arrays.asList("b", "banana"), reply.nameValueBlock);
        assertEquals(1, receiveCount.get());
    }

    public void testReplyWithNoData() throws Exception {
        // write the mocking script
        peer.sendFrame().synStream(0, 2, 0, 0, Arrays.asList("a", "android"));
        peer.acceptFrame();
        peer.play();

        // play it back
        final AtomicInteger receiveCount = new AtomicInteger();
        IncomingStreamHandler handler = new IncomingStreamHandler() {
            @Override public void receive(SpdyStream stream) throws IOException {
                stream.reply(Arrays.asList("b", "banana"), false);
                receiveCount.incrementAndGet();
            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.type);
        assertEquals(FLAG_FIN, reply.flags);
        assertEquals(Arrays.asList("b", "banana"), reply.nameValueBlock);
        assertEquals(1, receiveCount.get());
    }

    public void testNoop() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();
        connection.noop();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_NOOP, ping.type);
        assertEquals(0, ping.flags);
    }

    public void testServerPingsClient() throws Exception {
        // write the mocking script
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame();
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(0, ping.flags);
        assertEquals(2, ping.streamId);
    }

    public void testClientPingsServer() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().ping(0, 1);
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
        assertEquals(0, pingFrame.flags);
        assertEquals(1, pingFrame.streamId);
    }

    public void testUnexpectedPingIsNotReturned() throws Exception {
        // write the mocking script
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame();
        peer.sendFrame().ping(0, 3); // This ping will not be returned.
        peer.sendFrame().ping(0, 4);
        peer.acceptFrame();
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping2 = peer.takeFrame();
        assertEquals(2, ping2.streamId);
        MockSpdyPeer.InFrame ping4 = peer.takeFrame();
        assertEquals(4, ping4.streamId);
    }

    public void testServerSendsSettingsToClient() throws Exception {
        // write the mocking script
        Settings settings = new Settings();
        settings.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 10);
        peer.sendFrame().settings(Settings.FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS, settings);
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame();
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

    public void testMultipleSettingsFramesAreMerged() throws Exception {
        // write the mocking script
        Settings settings1 = new Settings();
        settings1.set(Settings.UPLOAD_BANDWIDTH, PERSIST_VALUE, 100);
        settings1.set(Settings.DOWNLOAD_BANDWIDTH, PERSIST_VALUE, 200);
        settings1.set(Settings.DOWNLOAD_RETRANS_RATE, 0, 300);
        peer.sendFrame().settings(0, settings1);
        Settings settings2 = new Settings();
        settings2.set(Settings.DOWNLOAD_BANDWIDTH, 0, 400);
        settings2.set(Settings.DOWNLOAD_RETRANS_RATE, PERSIST_VALUE, 500);
        settings2.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 600);
        peer.sendFrame().settings(0, settings2);
        peer.sendFrame().ping(0, 2);
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

    public void testBogusDataFrameDoesNotDisruptConnection() throws Exception {
        // write the mocking script
        peer.sendFrame().data(SpdyConnection.FLAG_FIN, 42, "bogus".getBytes("UTF-8"));
        peer.acceptFrame(); // RST_STREAM
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame(); // PING
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(0, rstStream.flags);
        assertEquals(42, rstStream.streamId);
        assertEquals(RST_INVALID_STREAM, rstStream.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.streamId);
    }

    public void testBogusReplyFrameDoesNotDisruptConnection() throws Exception {
        // write the mocking script
        peer.sendFrame().synReply(0, 42, Arrays.asList("a", "android"));
        peer.acceptFrame(); // RST_STREAM
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame(); // PING
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(0, rstStream.flags);
        assertEquals(42, rstStream.streamId);
        assertEquals(RST_INVALID_STREAM, rstStream.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.streamId);
    }

    public void testClientClosesClientOutputStream() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN_STREAM
        peer.acceptFrame(); // TYPE_DATA
        peer.acceptFrame(); // TYPE_DATA with FLAG_FIN
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame(); // PING response
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();
        SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
        OutputStream out = stream.getOutputStream();
        out.write("square".getBytes(UTF_8));
        out.flush();
        out.close();
        try {
            out.write("round".getBytes(UTF_8));
            fail();
        } catch (Exception expected) {
        }

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(0, synStream.flags);
        MockSpdyPeer.InFrame data = peer.takeFrame();
        assertEquals(TYPE_DATA, data.type);
        assertEquals(0, data.flags);
        assertTrue(Arrays.equals("square".getBytes("UTF-8"), data.data));
        MockSpdyPeer.InFrame fin = peer.takeFrame();
        assertEquals(TYPE_DATA, fin.type);
        assertEquals(FLAG_FIN, fin.flags);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(2, ping.streamId);
    }

    public void testServerClosesClientOutputStream() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN_STREAM
        peer.sendFrame().synReset(1, SpdyStream.RST_CANCEL);
        peer.acceptFrame(); // PING
        peer.sendFrame().ping(0, 1);
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
        }
        out.close();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(0, synStream.flags);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(1, ping.streamId);
    }

    /**
     * Test that the client sends a RST_STREAM if doing so won't disrupt the
     * output stream.
     */
    public void testClientClosesClientInputStream() throws Exception {
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
        }
        try {
            out.write('a');
            fail();
        } catch (IOException expected) {
        }

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(SpdyConnection.FLAG_FIN, synStream.flags);

        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(SpdyStream.RST_CANCEL, rstStream.statusCode);
    }

    /**
     * Test that the client doesn't send a RST_STREAM if doing so will disrupt
     * the output stream.
     */
    public void testClientClosesClientInputStreamIfOutputStreamIsClosed() throws Exception {
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
        }
        out.write("square".getBytes(UTF_8));
        out.flush();
        out.close();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(0, synStream.flags);

        MockSpdyPeer.InFrame data = peer.takeFrame();
        assertEquals(TYPE_DATA, data.type);
        assertTrue(Arrays.equals("square".getBytes("UTF-8"), data.data));

        MockSpdyPeer.InFrame fin = peer.takeFrame();
        assertEquals(TYPE_DATA, fin.type);
        assertEquals(FLAG_FIN, fin.flags);

        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(SpdyStream.RST_CANCEL, rstStream.statusCode);
    }

    public void testServerClosesClientInputStream() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN_STREAM
        peer.sendFrame().data(FLAG_FIN, 1, "square".getBytes(UTF_8));
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();
        SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), false, true);
        InputStream in = stream.getInputStream();
        assertStreamData("square", in);

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(SpdyConnection.FLAG_FIN, synStream.flags);
    }

    public void testRemoteDoubleReply() {
        // We should get a PROTOCOL ERROR
        // TODO
    }

    public void testRemoteSendsDataAfterInFinished() {
        // We have a bug where we don't fastfoward the stream
        // TODO
    }

    public void testRemoteSendsTooMuchData() {
        // We should send RST_FLOW_CONTROL_ERROR (and fastforward the stream)
        // TODO
    }

    public void testRemoteSendsRefusedStreamBeforeReplyHeaders() {
        // Calling getResponseHeaders() should throw an IOException if the stream is refused.
        // TODO
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
}
