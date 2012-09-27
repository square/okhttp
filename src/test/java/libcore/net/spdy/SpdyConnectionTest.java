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

package libcore.net.spdy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;

import static libcore.net.spdy.Settings.PERSIST_VALUE;
import static libcore.net.spdy.SpdyConnection.FLAG_FIN;
import static libcore.net.spdy.SpdyConnection.TYPE_PING;
import static libcore.net.spdy.SpdyConnection.TYPE_RST_STREAM;
import static libcore.net.spdy.SpdyConnection.TYPE_SYN_REPLY;
import static libcore.net.spdy.SpdyConnection.TYPE_SYN_STREAM;
import static libcore.net.spdy.SpdyStream.RST_INVALID_STREAM;

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
        assertEquals(TYPE_SYN_STREAM, synStream.reader.type);
        assertEquals(0, synStream.reader.flags);
        assertEquals(1, synStream.reader.id);
        assertEquals(0, synStream.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), synStream.reader.nameValueBlock);
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
                stream.reply(Arrays.asList("b", "banana"));

            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.reader.type);
        assertEquals(0, reply.reader.flags);
        assertEquals(2, reply.reader.id);
        assertEquals(0, reply.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), reply.reader.nameValueBlock);
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
                stream.replyNoContent(Arrays.asList("b", "banana"));
                receiveCount.incrementAndGet();
            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.reader.type);
        assertEquals(FLAG_FIN, reply.reader.flags);
        assertEquals(Arrays.asList("b", "banana"), reply.reader.nameValueBlock);
        assertEquals(1, receiveCount.get());
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
        assertEquals(TYPE_PING, ping.reader.type);
        assertEquals(0, ping.reader.flags);
        assertEquals(2, ping.reader.id);
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
        assertEquals(TYPE_PING, pingFrame.reader.type);
        assertEquals(0, pingFrame.reader.flags);
        assertEquals(1, pingFrame.reader.id);
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
        assertEquals(2, ping2.reader.id);
        MockSpdyPeer.InFrame ping4 = peer.takeFrame();
        assertEquals(4, ping4.reader.id);
    }

    public void testServerSendsSettingsToClient() throws Exception {
        // write the mocking script
        SpdyWriter newStream = peer.sendFrame();
        Settings settings = new Settings();
        settings.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 10);
        newStream.settings(Settings.FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS, settings);
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
        SpdyWriter newStream = peer.sendFrame();
        Settings settings1 = new Settings();
        settings1.set(Settings.UPLOAD_BANDWIDTH, PERSIST_VALUE, 100);
        settings1.set(Settings.DOWNLOAD_BANDWIDTH, PERSIST_VALUE, 200);
        settings1.set(Settings.DOWNLOAD_RETRANS_RATE, 0, 300);
        newStream.settings(0, settings1);
        Settings settings2 = new Settings();
        settings2.set(Settings.DOWNLOAD_BANDWIDTH, 0, 400);
        settings2.set(Settings.DOWNLOAD_RETRANS_RATE, PERSIST_VALUE, 500);
        settings2.set(Settings.MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 600);
        newStream.settings(0, settings2);
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
        assertEquals(TYPE_RST_STREAM, rstStream.reader.type);
        assertEquals(0, rstStream.reader.flags);
        assertEquals(8, rstStream.reader.length);
        assertEquals(42, rstStream.reader.id);
        assertEquals(RST_INVALID_STREAM, rstStream.reader.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.reader.id);
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
        assertEquals(TYPE_RST_STREAM, rstStream.reader.type);
        assertEquals(0, rstStream.reader.flags);
        assertEquals(8, rstStream.reader.length);
        assertEquals(42, rstStream.reader.id);
        assertEquals(RST_INVALID_STREAM, rstStream.reader.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.reader.id);
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
