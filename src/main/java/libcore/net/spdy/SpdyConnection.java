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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A socket connection to a remote peer. A connection hosts streams which can
 * send and receive data.
 */
public final class SpdyConnection implements Closeable {

    /*
     * Socket writes are guarded by this. Socket reads are unguarded but are
     * only made by the reader thread.
     */

    // TODO: break up synchronization: Sync on SpdyWriter and 'this' independently.
    //       SpdyWriter: I/O. Held for a long time.
    //       This: state, both incoming and outgoing. Settings, pings. Not held for a long time.

    static final int FLAG_FIN = 0x1;
    static final int FLAG_UNIDIRECTIONAL = 0x2;

    /** Peer request to clear durable settings. */
    static final int FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS = 0x1;
    /** Sent by servers only. The peer requests this setting persisted for future connections. */
    static final int FLAG_SETTINGS_PERSIST_VALUE = 0x1;
    /** Sent by clients only. The client is reminding the server of a persisted value. */
    static final int FLAG_SETTINGS_PERSISTED = 0x2;
    /** Sender's estimate of max incoming kbps. */
    static final int SETTINGS_UPLOAD_BANDWIDTH = 0x1;
    /** Sender's estimate of max outgoing kbps. */
    static final int SETTINGS_DOWNLOAD_BANDWIDTH = 0x2;
    /** Sender's estimate of milliseconds between sending a request and receiving a response. */
    static final int SETTINGS_ROUND_TRIP_TIME = 0x3;
    /** Sender's maximum number of concurrent streams. */
    static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x4;
    /** Current CWND in Packets. */
    static final int SETTINGS_CURRENT_CWND = 0x5;
    /** Retransmission rate. Percentage */
    static final int SETTINGS_DOWNLOAD_RETRANS_RATE = 0x6;
    /** Window size in bytes. */
    static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x7;

    static final int TYPE_EOF = -1;
    static final int TYPE_DATA = 0x0;
    static final int TYPE_SYN_STREAM = 0x1;
    static final int TYPE_SYN_REPLY = 0x2;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_NOOP = 0x5;
    static final int TYPE_PING = 0x6;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_HEADERS = 0x8;
    static final int VERSION = 2;

    /** Guarded by this. */
    private int nextStreamId;
    /** Guarded by this. */
    private int nextPingId;
    private final SpdyReader spdyReader;
    private final SpdyWriter spdyWriter;
    private final Executor executor;

    /** The maximum number of concurrent streams permitted by the peer, or -1 for no limit. */
    int peerMaxConcurrentStreams;

    /**
     * User code to run in response to an incoming stream. This must not be run
     * on the read thread, otherwise a deadlock is possible.
     */
    private final IncomingStreamHandler handler;

    private final Map<Integer, SpdyStream> streams = Collections.synchronizedMap(
            new HashMap<Integer, SpdyStream>());

    /** Lazily-created map of in-flight pings awaiting a response. Guarded by this. */
    private Map<Integer, Ping> pings;

    private SpdyConnection(Builder builder) {
        nextStreamId = builder.client ? 1 : 2;
        nextPingId = builder.client ? 1 : 2;
        spdyReader = new SpdyReader(builder.in);
        spdyWriter = new SpdyWriter(builder.out);
        handler = builder.handler;
        clearSettings();

        String name = isClient() ? "ClientReader" : "ServerReader";
        executor = builder.executor != null
                ? builder.executor
                : Executors.newCachedThreadPool(Threads.newThreadFactory(name, true));
        executor.execute(new Reader());
    }

    /**
     * Returns true if this peer initiated the connection.
     */
    public synchronized boolean isClient() {
        return nextStreamId % 2 == 1;
    }

    /**
     * Resets this connection's settings to their default values.
     */
    private synchronized void clearSettings() {
        peerMaxConcurrentStreams = -1;
    }

    /**
     * Receive an incoming setting from a peer. This SPDY client doesn't care
     * about most settings, and so it doesn't save them.
     * https://github.com/square/okhttp/issues/32
     */
    private synchronized void receiveSetting(int id, int idFlags, int value) {
        switch (id) {
        case SETTINGS_MAX_CONCURRENT_STREAMS:
            peerMaxConcurrentStreams = value;
            break;
        }
    }

    private SpdyStream getStream(int id) {
        SpdyStream stream = streams.get(id);
        if (stream == null) {
            throw new UnsupportedOperationException("TODO " + id + "; " + streams); // TODO: rst stream
        }
        return stream;
    }

    void removeStream(int streamId) {
        streams.remove(streamId);
    }

    /**
     * Returns a new locally-initiated stream.
     *
     * @param out true to create an output stream that we can use to send data
     *     to the remote peer. Corresponds to {@code FLAG_FIN}.
     * @param in true to create an input stream that the remote peer can use to
     *     send data to us. Corresponds to {@code FLAG_UNIDIRECTIONAL}.
     */
    public synchronized SpdyStream newStream(List<String> requestHeaders, boolean out, boolean in)
            throws IOException {
        int streamId = nextStreamId; // TODO
        nextStreamId += 2;
        int flags = (out ? 0 : FLAG_FIN) | (in ? 0 : FLAG_UNIDIRECTIONAL);
        int associatedStreamId = 0;  // TODO
        int priority = 0; // TODO

        SpdyStream result = new SpdyStream(streamId, this, requestHeaders, flags);
        streams.put(streamId, result);

        spdyWriter.flags = flags;
        spdyWriter.id = streamId;
        spdyWriter.associatedId = associatedStreamId;
        spdyWriter.priority = priority;
        spdyWriter.nameValueBlock = requestHeaders;
        spdyWriter.synStream();

        return result;
    }

    synchronized void writeSynReply(int streamId, List<String> alternating) throws IOException {
        int flags = 0; // TODO
        spdyWriter.flags = flags;
        spdyWriter.id = streamId;
        spdyWriter.nameValueBlock = alternating;
        spdyWriter.synReply();
    }

    /** Writes a complete data frame. */
    synchronized void writeFrame(byte[] bytes, int offset, int length) throws IOException {
        spdyWriter.out.write(bytes, offset, length);
    }

    void writeSynResetLater(final int streamId, final int statusCode) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    writeSynReset(streamId, statusCode);
                } catch (IOException ignored) {
                }
            }
        });
    }

    synchronized void writeSynReset(int streamId, int statusCode) throws IOException {
        int flags = 0; // TODO
        spdyWriter.flags = flags;
        spdyWriter.id = streamId;
        spdyWriter.statusCode = statusCode;
        spdyWriter.synReset();
    }

    /**
     * Sends a ping to the peer. Use the returned object to await the ping's
     * response and observe its round trip time.
     */
    public Ping ping() throws IOException {
        Ping ping = new Ping();
        int pingId;
        synchronized (this) {
            if (pings == null) pings = new HashMap<Integer, Ping>();
            pingId = nextPingId;
            nextPingId += 2;
            pings.put(pingId, ping);
        }
        writePingLater(pingId, ping);
        return ping;
    }

    void writePingLater(final int id, final Ping ping) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    writePing(id, ping);
                } catch (IOException ignored) {
                }
            }
        });
    }

    synchronized void writePing(int id, Ping ping) throws IOException {
        spdyWriter.flags = 0;
        spdyWriter.id = id;
        // Observe the sent time immediately before performing I/O.
        if (ping != null) ping.send();
        spdyWriter.ping();
    }

    public synchronized void flush() throws IOException {
        spdyWriter.out.flush();
    }

    @Override public synchronized void close() throws IOException {
        // TODO: graceful close; send RST frames
        // TODO: close all streams to release waiting readers
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdown();
        }
    }

    public static class Builder {
        private InputStream in;
        private OutputStream out;
        private IncomingStreamHandler handler = IncomingStreamHandler.REFUSE_INCOMING_STREAMS;
        private Executor executor;
        public boolean client;

        /**
         * @param client true if this peer initiated the connection; false if
         *     this peer accepted the connection.
         */
        public Builder(boolean client, Socket socket) throws IOException {
            this(client, socket.getInputStream(), socket.getOutputStream());
        }

        /**
         * @param client true if this peer initiated the connection; false if this
         *     peer accepted the connection.
         */
        public Builder(boolean client, InputStream in, OutputStream out) {
            this.client = client;
            this.in = in;
            this.out = out;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder handler(IncomingStreamHandler handler) {
            this.handler = handler;
            return this;
        }

        public SpdyConnection build() {
            return new SpdyConnection(this);
        }
    }

    private class Reader implements Runnable {
        @Override public void run() {
            try {
                while (readFrame()) {
                }
                close();
            } catch (Throwable e) {
                e.printStackTrace(); // TODO
            }
        }

        /**
         * Returns true unless the last frame has been read.
         */
        private boolean readFrame() throws IOException {
            switch (spdyReader.nextFrame()) {
            case TYPE_EOF:
                return false;

            case TYPE_DATA:
                getStream(spdyReader.id)
                        .receiveData(spdyReader.in, spdyReader.flags, spdyReader.length);
                return true;

            case TYPE_SYN_STREAM:
                final SpdyStream stream = new SpdyStream(spdyReader.id, SpdyConnection.this,
                        spdyReader.nameValueBlock, spdyReader.flags);
                SpdyStream previous = streams.put(spdyReader.id, stream);
                if (previous != null) {
                    previous.close(SpdyStream.RST_PROTOCOL_ERROR);
                }
                executor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            handler.receive(stream);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                return true;

            case TYPE_SYN_REPLY:
                // TODO: honor flags
                getStream(spdyReader.id).receiveReply(spdyReader.nameValueBlock);
                return true;

            case TYPE_RST_STREAM:
                getStream(spdyReader.id).receiveRstStream(spdyReader.statusCode);
                return true;

            case SpdyConnection.TYPE_SETTINGS:
                int numberOfEntries = spdyReader.in.readInt();
                if (spdyReader.length != 4 + numberOfEntries * 8) {
                    // TODO: DIE
                }
                if ((spdyReader.flags & FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS) != 0) {
                    clearSettings();
                }
                for (int i = 0; i < numberOfEntries; i++) {
                    int w1 = spdyReader.in.readInt();
                    int value = spdyReader.in.readInt();
                    // The ID is a 24 bit little-endian value, so 0xabcdefxx becomes 0x00efcdab.
                    int id = ((w1 & 0xff000000) >>> 24)
                            | ((w1 & 0xff0000) >>> 8)
                            | ((w1 & 0xff00) << 8);
                    int idFlags = (w1 & 0xff);
                    receiveSetting(id, idFlags, value);
                }
                return true;

            case SpdyConnection.TYPE_NOOP:
                throw new UnsupportedOperationException();

            case SpdyConnection.TYPE_PING:
                int id = spdyReader.id;
                if (isClient() != (id % 2 == 1)) {
                    // Respond to a client ping if this is a server and vice versa.
                    writePingLater(id, null);
                } else {
                    Ping ping;
                    synchronized (this) {
                        ping = pings != null ? pings.remove(id) : null;
                    }
                    if (ping != null) {
                        ping.receive();
                    }
                }
                return true;

            case SpdyConnection.TYPE_GOAWAY:
            case SpdyConnection.TYPE_HEADERS:
                throw new UnsupportedOperationException();

            default:
                // TODO: throw IOException here?
                return false;
            }
        }
    }
}
