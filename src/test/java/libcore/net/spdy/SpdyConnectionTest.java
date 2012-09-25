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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        SpdyWriter reply = peer.sendFrame();
        reply.id = 1;
        reply.nameValueBlock = Arrays.asList("a", "android");
        reply.synReply();
        SpdyWriter replyData = peer.sendFrame();
        replyData.flags = SpdyConnection.FLAG_FIN;
        replyData.id = 1;
        replyData.data("robot".getBytes("UTF-8"));
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        List<String> responseHeaders = stream.getResponseHeaders();
        assertEquals(Arrays.asList("a", "android"), responseHeaders);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.getInputStream()));
        assertEquals("robot", reader.readLine());
        assertEquals(null, reader.readLine());
        OutputStream out = stream.getOutputStream();
        out.write("c3po".getBytes("UTF-8"));
        out.close();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(0, synStream.reader.flags);
        assertEquals(1, synStream.reader.id);
        assertEquals(0, synStream.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), synStream.reader.nameValueBlock);
        MockSpdyPeer.InFrame requestData = peer.takeFrame();
        assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
    }

    public void testServerCreatesStreamAndClientReplies() throws Exception {
        // write the mocking script
        SpdyWriter newStream = peer.sendFrame();
        newStream.flags = 0;
        newStream.id = 2;
        newStream.associatedId = 0;
        newStream.nameValueBlock = Arrays.asList("a", "android");
        newStream.synStream();
        peer.acceptFrame();
        peer.play();

        // play it back
        IncomingStreamHandler handler = new IncomingStreamHandler() {
            @Override public void receive(SpdyStream stream) throws IOException {
                assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
                assertEquals(-1, stream.getRstStatusCode());
                stream.reply(Arrays.asList("b", "banana"));
            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(0, synStream.reader.flags);
        assertEquals(2, synStream.reader.id);
        assertEquals(0, synStream.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), synStream.reader.nameValueBlock);
    }

    public void testServerPingsClient() throws Exception {
        // write the mocking script
        peer.sendPing(2);
        peer.acceptFrame();
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(0, ping.reader.flags);
        assertEquals(2, ping.reader.id);
    }

    public void testClientPingsServer() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendPing(1);
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
        assertEquals(0, pingFrame.reader.flags);
        assertEquals(1, pingFrame.reader.id);
    }

    public void testUnexpectedPingIsNotReturned() throws Exception {
        // write the mocking script
        peer.sendPing(2);
        peer.acceptFrame();
        peer.sendPing(3); // This ping will not be returned.
        peer.sendPing(4);
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
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
        newStream.flags = SpdyConnection.FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS;
        newStream.settings(1);
        newStream.setting(SpdyConnection.SETTINGS_MAX_CONCURRENT_STREAMS,
                SpdyConnection.FLAG_SETTINGS_PERSIST_VALUE, 10);
        peer.sendPing(2);
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        peer.takeFrame(); // Guarantees that the Settings frame has been processed.
        synchronized (connection) {
            assertEquals(10, connection.peerMaxConcurrentStreams);
        }
    }
}
