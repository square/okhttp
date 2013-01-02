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

import static com.squareup.okhttp.internal.Util.UTF_8;
import static com.squareup.okhttp.internal.spdy.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.FLAG_FIN;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.FLAG_UNIDIRECTIONAL;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_DATA;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_GOAWAY;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_NOOP;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_PING;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_RST_STREAM;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_SYN_REPLY;
import static com.squareup.okhttp.internal.spdy.SpdyConnection.TYPE_SYN_STREAM;
import static com.squareup.okhttp.internal.spdy.SpdyStream.RST_FLOW_CONTROL_ERROR;
import static com.squareup.okhttp.internal.spdy.SpdyStream.RST_INVALID_STREAM;
import static com.squareup.okhttp.internal.spdy.SpdyStream.RST_PROTOCOL_ERROR;
import static com.squareup.okhttp.internal.spdy.SpdyStream.RST_REFUSED_STREAM;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public final class SpdyConnectionTest {
    private static final IncomingStreamHandler REJECT_INCOMING_STREAMS
            = new IncomingStreamHandler() {
        @Override public void receive(SpdyStream stream) throws IOException {
            throw new AssertionError();
        }
    };
    private final MockSpdyPeer peer = new MockSpdyPeer();

    @Test public void clientCreatesStreamAndServerReplies() throws Exception {
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
        assertEquals(0, connection.openStreamCount());

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

    @Test public void headersOnlyStreamIsClosedImmediately() throws Exception {
        peer.acceptFrame(); // SYN STREAM
        peer.sendFrame().synReply(0, 1, Arrays.asList("b", "banana"));
        peer.play();

        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        connection.newStream(Arrays.asList("a", "android"), false, false);
        assertEquals(0, connection.openStreamCount());
    }

    @Test public void clientCreatesStreamAndServerRepliesWithFin() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM
        peer.acceptFrame(); // PING
        peer.sendFrame().synReply(FLAG_FIN, 1, Arrays.asList("a", "android"));
        peer.sendFrame().ping(0, 1);
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        connection.newStream(Arrays.asList("b", "banana"), false, true);
        assertEquals(1, connection.openStreamCount());
        connection.ping().roundTripTime(); // Ensure that the SYN_REPLY has been received.
        assertEquals(0, connection.openStreamCount());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
    }

    @Test public void serverCreatesStreamAndClientReplies() throws Exception {
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

    @Test public void replyWithNoData() throws Exception {
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

    @Test public void noop() throws Exception {
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

    @Test public void serverPingsClient() throws Exception {
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

    @Test public void clientPingsServer() throws Exception {
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

    @Test public void unexpectedPingIsNotReturned() throws Exception {
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

    @Test public void serverSendsSettingsToClient() throws Exception {
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

    @Test public void multipleSettingsFramesAreMerged() throws Exception {
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

    @Test public void bogusDataFrameDoesNotDisruptConnection() throws Exception {
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

    @Test public void bogusReplyFrameDoesNotDisruptConnection() throws Exception {
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

    @Test public void clientClosesClientOutputStream() throws Exception {
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
        assertEquals(0, connection.openStreamCount());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(FLAG_UNIDIRECTIONAL, synStream.flags);
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

    @Test public void serverClosesClientOutputStream() throws Exception {
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
            assertEquals("stream was reset: CANCEL", expected.getMessage());
        }
        out.close();
        assertEquals(0, connection.openStreamCount());

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

    @Test public void serverClosesClientInputStream() throws Exception {
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
        assertEquals(0, connection.openStreamCount());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        assertEquals(SpdyConnection.FLAG_FIN, synStream.flags);
    }

    @Test public void remoteDoubleSynReply() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().synReply(0, 1, Arrays.asList("a", "android"));
        peer.acceptFrame(); // PING
        peer.sendFrame().synReply(0, 1, Arrays.asList("b", "banana"));
        peer.sendFrame().ping(0, 1);
        peer.acceptFrame(); // RST STREAM
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("c", "cola"), true, true);
        assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
        connection.ping().roundTripTime(); // Ensure that the 2nd SYN REPLY has been received.
        try {
            stream.getInputStream().read();
            fail();
        } catch (IOException e) {
            assertEquals("stream was reset: PROTOCOL_ERROR", e.getMessage());
        }

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(1, rstStream.streamId);
        assertEquals(0, rstStream.flags);
        assertEquals(RST_PROTOCOL_ERROR, rstStream.statusCode);
    }

    @Test public void remoteDoubleSynStream() throws Exception {
        // write the mocking script
        peer.sendFrame().synStream(0, 2, 0, 0, Arrays.asList("a", "android"));
        peer.acceptFrame();
        peer.sendFrame().synStream(0, 2, 0, 0, Arrays.asList("b", "banana"));
        peer.acceptFrame();
        peer.play();

        // play it back
        final AtomicInteger receiveCount = new AtomicInteger();
        IncomingStreamHandler handler = new IncomingStreamHandler() {
            @Override public void receive(SpdyStream stream) throws IOException {
                receiveCount.incrementAndGet();
                assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
                assertEquals(-1, stream.getRstStatusCode());
                stream.reply(Arrays.asList("c", "cola"), true);
            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.type);
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(2, rstStream.streamId);
        assertEquals(0, rstStream.flags);
        assertEquals(RST_PROTOCOL_ERROR, rstStream.statusCode);
        assertEquals(1, receiveCount.intValue());
    }

    @Test public void remoteSendsDataAfterInFinished() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().synReply(0, 1, Arrays.asList("a", "android"));
        peer.sendFrame().data(SpdyConnection.FLAG_FIN, 1, "robot".getBytes("UTF-8"));
        peer.sendFrame().data(SpdyConnection.FLAG_FIN, 1, "c3po".getBytes("UTF-8")); // Ignored.
        peer.sendFrame().ping(0, 2); // Ping just to make sure the stream was fastforwarded.
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
        assertStreamData("robot", stream.getInputStream());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(2, ping.streamId);
        assertEquals(0, ping.flags);
    }

    @Test public void remoteSendsTooMuchData() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().synReply(0, 1, Arrays.asList("b", "banana"));
        peer.sendFrame().data(0, 1, new byte[64 * 1024 + 1]);
        peer.acceptFrame();
        peer.sendFrame().ping(0, 2); // Ping just to make sure the stream was fastforwarded.
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("a", "android"), true, true);
        assertEquals(Arrays.asList("b", "banana"), stream.getResponseHeaders());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(1, rstStream.streamId);
        assertEquals(0, rstStream.flags);
        assertEquals(RST_FLOW_CONTROL_ERROR, rstStream.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(2, ping.streamId);
    }

    @Test public void remoteSendsRefusedStreamBeforeReplyHeaders() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendFrame().synReset(1, RST_REFUSED_STREAM);
        peer.sendFrame().ping(0, 2);
        peer.acceptFrame();
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
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        assertEquals(2, ping.streamId);
        assertEquals(0, ping.flags);
    }

    @Test public void receiveGoAway() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM 1
        peer.acceptFrame(); // SYN STREAM 3
        peer.sendFrame().goAway(0, 1);
        peer.acceptFrame(); // PING
        peer.sendFrame().ping(0, 1);
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
        assertEquals(TYPE_SYN_STREAM, synStream1.type);
        MockSpdyPeer.InFrame synStream2 = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream2.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        MockSpdyPeer.InFrame data1 = peer.takeFrame();
        assertEquals(TYPE_DATA, data1.type);
        assertEquals(1, data1.streamId);
        assertTrue(Arrays.equals("abcdef".getBytes("UTF-8"), data1.data));
    }

    @Test public void sendGoAway() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM 1
        peer.acceptFrame(); // GOAWAY
        peer.acceptFrame(); // PING
        peer.sendFrame().synStream(0, 2, 0, 0, Arrays.asList("b", "banana")); // Should be ignored!
        peer.sendFrame().ping(0, 1);
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        connection.newStream(Arrays.asList("a", "android"), true, true);
        Ping ping = connection.ping();
        connection.shutdown();
        ping.roundTripTime(); // Ensure that the SYN STREAM has been received.
        assertEquals(1, connection.openStreamCount());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream1 = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream1.type);
        MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
        assertEquals(TYPE_PING, pingFrame.type);
        MockSpdyPeer.InFrame goaway = peer.takeFrame();
        assertEquals(TYPE_GOAWAY, goaway.type);
        assertEquals(0, goaway.streamId);
    }

    @Test public void noPingsAfterShutdown() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // GOAWAY
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        connection.shutdown();
        try {
            connection.ping();
            fail();
        } catch (IOException expected) {
            assertEquals("shutdown", expected.getMessage());
        }

        // verify the peer received what was expected
        MockSpdyPeer.InFrame goaway = peer.takeFrame();
        assertEquals(TYPE_GOAWAY, goaway.type);
    }

    @Test public void close() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM
        peer.acceptFrame(); // GOAWAY
        peer.acceptFrame(); // RST STREAM
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
        assertEquals(TYPE_SYN_STREAM, synStream.type);
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
        peer.acceptFrame(); // SYN STREAM
        peer.sendFrame().synReply(0, 1, Arrays.asList("a", "android"));
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

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
    }

    @Test public void headers() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM
        peer.acceptFrame(); // PING
        peer.sendFrame().synReply(0, 1, Arrays.asList("a", "android"));
        peer.sendFrame().headers(0, 1, Arrays.asList("c", "c3po"));
        peer.sendFrame().ping(0, 1);
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
        assertEquals(Arrays.asList("a", "android", "c", "c3po"), stream.getResponseHeaders());

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
    }

    @Test public void headersBeforeReply() throws Exception {
        // write the mocking script
        peer.acceptFrame(); // SYN STREAM
        peer.acceptFrame(); // PING
        peer.sendFrame().headers(0, 1, Arrays.asList("c", "c3po"));
        peer.acceptFrame(); // RST STREAM
        peer.sendFrame().ping(0, 1);
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        connection.ping().roundTripTime(); // Ensure that the HEADERS has been received.
        try {
            stream.getResponseHeaders();
            fail();
        } catch (IOException e) {
            assertEquals("stream was reset: PROTOCOL_ERROR", e.getMessage());
        }

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.type);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.type);
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.type);
        assertEquals(RST_PROTOCOL_ERROR, rstStream.statusCode);
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
