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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import libcore.io.Streams;

import static libcore.net.spdy.Threads.newThreadFactory;

/**
 * A socket connection to a remote peer. A connection hosts streams which can
 * send and receive data.
 *
 * <p>Many methods in this API are <strong>synchronous:</strong> the call is
 * completed before the method returns. This is typical for Java but atypical
 * for SPDY. This is motivated by exception transparency: an IOException that
 * was triggered by a certain caller can be caught and handled by that caller.
 */
public final class SpdyConnection implements Closeable {

    /*
     * Internal state of this connection is guarded by 'this'. No blocking
     * operations may be performed while holding this lock!
     *
     * Socket writes are guarded by spdyWriter.
     *
     * Socket reads are unguarded but are only made by the reader thread.
     *
     * Certain operations (like SYN_STREAM) need to synchronize on both the
     * spdyWriter (to do blocking I/O) and this (to create streams). Such
     * operations must synchronize on 'this' last. This ensures that we never
     * wait for a blocking operation while holding 'this'.
     */

    static final int FLAG_FIN = 0x1;
    static final int FLAG_UNIDIRECTIONAL = 0x2;

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

    private final SpdyReader spdyReader;
    private final SpdyWriter spdyWriter;
    private final ExecutorService readExecutor;
    private final ExecutorService writeExecutor;
    private final ExecutorService callbackExecutor;

    /**
     * User code to run in response to an incoming stream. Callbacks must not be
     * run on the callback executor.
     */
    private final IncomingStreamHandler handler;

    private final Map<Integer, SpdyStream> streams = new HashMap<Integer, SpdyStream>();
    private int nextStreamId;

    /** Lazily-created map of in-flight pings awaiting a response. Guarded by this. */
    private Map<Integer, Ping> pings;
    private int nextPingId;

    /** Lazily-created settings for this connection. */
    Settings settings;

    private SpdyConnection(Builder builder) {
        spdyReader = new SpdyReader(builder.in);
        spdyWriter = new SpdyWriter(builder.out);
        handler = builder.handler;
        nextStreamId = builder.client ? 1 : 2;
        nextPingId = builder.client ? 1 : 2;

        String prefix = builder.client ? "Spdy Client " : "Spdy Server ";
        readExecutor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), newThreadFactory(prefix + "Reader", false));
        writeExecutor = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), newThreadFactory(prefix + "Writer", false));
        callbackExecutor = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), newThreadFactory(prefix + "Callbacks", false));

        readExecutor.execute(new Reader());
    }

    /**
     * Returns true if this peer initiated the connection.
     */
    public synchronized boolean isClient() {
        return nextStreamId % 2 == 1;
    }

    /**
     * Receive an incoming setting from a peer.
     */
    private synchronized void receiveSettings(int flags, Settings settings) {
        if (this.settings == null
                || (flags & Settings.FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS) != 0) {
            this.settings = settings;
        } else {
            this.settings.merge(settings);
        }
    }

    private synchronized SpdyStream getStream(int id) {
        return streams.get(id);
    }

    synchronized SpdyStream removeStream(int streamId) {
        return streams.remove(streamId);
    }

    /**
     * Returns a new locally-initiated stream.
     *
     * @param out true to create an output stream that we can use to send data
     *     to the remote peer. Corresponds to {@code FLAG_FIN}.
     * @param in true to create an input stream that the remote peer can use to
     *     send data to us. Corresponds to {@code FLAG_UNIDIRECTIONAL}.
     */
    public SpdyStream newStream(List<String> requestHeaders, boolean out, boolean in)
            throws IOException {
        int flags = (out ? 0 : FLAG_FIN) | (in ? 0 : FLAG_UNIDIRECTIONAL);
        int associatedStreamId = 0;  // TODO: permit the caller to specify an associated stream.
        int priority = 0; // TODO: permit the caller to specify a priority.
        SpdyStream stream;
        int streamId;

        synchronized (spdyWriter) {
            synchronized (this) {
                streamId = nextStreamId;
                nextStreamId += 2;
                stream = new SpdyStream(streamId, this, requestHeaders, flags);
                streams.put(streamId, stream);
            }

            spdyWriter.synStream(flags, streamId, associatedStreamId, priority, requestHeaders);
        }

        return stream;
    }

    void writeSynReply(int streamId, int flags, List<String> alternating) throws IOException {
        synchronized (spdyWriter) {
            spdyWriter.synReply(flags, streamId, alternating);
        }
    }

    /** Writes a complete data frame. */
    void writeFrame(byte[] bytes, int offset, int length) throws IOException {
        synchronized (spdyWriter) {
            spdyWriter.out.write(bytes, offset, length);
        }
    }

    void writeSynResetLater(final int streamId, final int statusCode) {
        writeExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    writeSynReset(streamId, statusCode);
                } catch (IOException ignored) {
                }
            }
        });
    }

    void writeSynReset(int streamId, int statusCode) throws IOException {
        synchronized (spdyWriter) {
            spdyWriter.synReset(streamId, statusCode);
        }
    }

    /**
     * Sends a ping frame to the peer. Use the returned object to await the
     * ping's response and observe its round trip time.
     */
    public synchronized Ping ping() {
        Ping ping = new Ping();
        int pingId = nextPingId;
        nextPingId += 2;
        if (pings == null) pings = new HashMap<Integer, Ping>();
        pings.put(pingId, ping);
        writePingLater(pingId, ping);
        return ping;
    }

    private void writePingLater(final int id, final Ping ping) {
        writeExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    writePing(id, ping);
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void writePing(int id, Ping ping) throws IOException {
        // Observe the sent time immediately before performing I/O.
        if (ping != null) ping.send();

        synchronized (spdyWriter) {
            spdyWriter.ping(0, id);
        }
    }

    private synchronized Ping removePing(int id) {
        return pings != null ? pings.remove(id) : null;
    }

    public void flush() throws IOException {
        synchronized (spdyWriter) {
            spdyWriter.out.flush();
        }
    }

    @Override public void close() throws IOException {
        close(null);
    }

    private synchronized void close(Throwable reason) throws IOException {
        // TODO: forward 'reason' to forced closed streams?
        // TODO: graceful close; send RST frames
        // TODO: close all streams to release waiting readers
        writeExecutor.shutdown();
        readExecutor.shutdown();
        callbackExecutor.shutdown();
    }

    public static class Builder {
        private InputStream in;
        private OutputStream out;
        private IncomingStreamHandler handler = IncomingStreamHandler.REFUSE_INCOMING_STREAMS;
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
            Throwable failure = null;
            try {
                while (readFrame()) {
                }
            } catch (Throwable e) {
                failure = e;
            }

            try {
                close(failure);
            } catch (IOException ignored) {
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
                SpdyStream dataStream = getStream(spdyReader.id);
                if (dataStream != null) {
                    dataStream.receiveData(spdyReader.in, spdyReader.flags, spdyReader.length);
                } else {
                    writeSynResetLater(spdyReader.id, SpdyStream.RST_INVALID_STREAM);
                    Streams.skipByReading(spdyReader.in, spdyReader.length);
                }
                return true;

            case TYPE_SYN_STREAM:
                final SpdyStream synStream = new SpdyStream(spdyReader.id, SpdyConnection.this,
                        spdyReader.nameValueBlock, spdyReader.flags);
                SpdyStream previous = streams.put(spdyReader.id, synStream);
                if (previous != null) {
                    previous.close(SpdyStream.RST_PROTOCOL_ERROR);
                }
                callbackExecutor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            handler.receive(synStream);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                return true;

            case TYPE_SYN_REPLY:
                SpdyStream replyStream = getStream(spdyReader.id);
                if (replyStream != null) {
                    // TODO: honor incoming FLAG_FIN.
                    replyStream.receiveReply(spdyReader.nameValueBlock);
                } else {
                    writeSynResetLater(spdyReader.id, SpdyStream.RST_INVALID_STREAM);
                }
                return true;

            case TYPE_RST_STREAM:
                SpdyStream rstStream = removeStream(spdyReader.id);
                if (rstStream != null) {
                    rstStream.receiveRstStream(spdyReader.statusCode);
                }
                return true;

            case SpdyConnection.TYPE_SETTINGS:
                receiveSettings(spdyReader.flags, spdyReader.settings);
                return true;

            case SpdyConnection.TYPE_NOOP:
                throw new UnsupportedOperationException();

            case SpdyConnection.TYPE_PING:
                int id = spdyReader.id;
                if (isClient() != (id % 2 == 1)) {
                    // Respond to a client ping if this is a server and vice versa.
                    writePingLater(id, null);
                } else {
                    Ping ping = removePing(id);
                    if (ping != null) {
                        ping.receive();
                    }
                }
                return true;

            case SpdyConnection.TYPE_GOAWAY:
            case SpdyConnection.TYPE_HEADERS:
                throw new UnsupportedOperationException();

            default:
                throw new IOException("Unexpected frame: " + Integer.toHexString(spdyReader.type));
            }
        }
    }
}
