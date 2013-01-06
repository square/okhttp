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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    static final int TYPE_DATA = 0x0;
    static final int TYPE_SYN_STREAM = 0x1;
    static final int TYPE_SYN_REPLY = 0x2;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_NOOP = 0x5;
    static final int TYPE_PING = 0x6;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_HEADERS = 0x8;
    static final int TYPE_WINDOW_UPDATE = 0x9;
    static final int TYPE_CREDENTIAL = 0x10;
    static final int VERSION = 3;

    static final int GOAWAY_OK = 0;
    static final int GOAWAY_PROTOCOL_ERROR = 1;
    static final int GOAWAY_INTERNAL_ERROR = 2;

    /**
     * True if this peer initiated the connection.
     */
    final boolean client;

    /**
     * User code to run in response to an incoming stream. Callbacks must not be
     * run on the callback executor.
     */
    private final IncomingStreamHandler handler;

    private final SpdyReader spdyReader;
    private final SpdyWriter spdyWriter;
    private final ExecutorService readExecutor;
    private final ExecutorService writeExecutor;
    private final ExecutorService callbackExecutor;

    private final Map<Integer, SpdyStream> streams = new HashMap<Integer, SpdyStream>();
    private int lastGoodStreamId;
    private int nextStreamId;
    private boolean shutdown;

    /** Lazily-created map of in-flight pings awaiting a response. Guarded by this. */
    private Map<Integer, Ping> pings;
    private int nextPingId;

    /** Lazily-created settings for this connection. */
    Settings settings;

    private SpdyConnection(Builder builder) {
        client = builder.client;
        handler = builder.handler;
        spdyReader = new SpdyReader(builder.in);
        spdyWriter = new SpdyWriter(builder.out);
        nextStreamId = builder.client ? 1 : 2;
        nextPingId = builder.client ? 1 : 2;

        String prefix = builder.client ? "Spdy Client " : "Spdy Server ";
        readExecutor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), Threads.newThreadFactory(prefix + "Reader", false));
        writeExecutor = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), Threads.newThreadFactory(prefix + "Writer", false));
        callbackExecutor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), Threads.newThreadFactory(prefix + "Callbacks", false));

        readExecutor.execute(new Reader());
    }

    /**
     * Returns the number of {@link SpdyStream#isOpen() open streams} on this
     * connection.
     */
    public synchronized int openStreamCount() {
        return streams.size();
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
        int associatedStreamId = 0;  // TODO: permit the caller to specify an associated stream?
        int priority = 0; // TODO: permit the caller to specify a priority?
        int slot = 0; // TODO: permit the caller to specify a slot?
        SpdyStream stream;
        int streamId;

        synchronized (spdyWriter) {
            synchronized (this) {
                if (shutdown) {
                    throw new IOException("shutdown");
                }
                streamId = nextStreamId;
                nextStreamId += 2;
                stream = new SpdyStream(streamId, this, flags, priority, slot, requestHeaders);
                if (stream.isOpen()) {
                    streams.put(streamId, stream);
                }
            }

            spdyWriter.synStream(flags, streamId, associatedStreamId, priority, slot,
                    requestHeaders);
        }

        return stream;
    }

    void writeSynReply(int streamId, int flags, List<String> alternating) throws IOException {
        spdyWriter.synReply(flags, streamId, alternating);
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
        spdyWriter.rstStream(streamId, statusCode);
    }

    /**
     * Sends a ping frame to the peer. Use the returned object to await the
     * ping's response and observe its round trip time.
     */
    public Ping ping() throws IOException {
        Ping ping = new Ping();
        int pingId;
        synchronized (this) {
            if (shutdown) {
                throw new IOException("shutdown");
            }
            pingId = nextPingId;
            nextPingId += 2;
            if (pings == null) pings = new HashMap<Integer, Ping>();
            pings.put(pingId, ping);
        }
        writePing(pingId, ping);
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
        synchronized (spdyWriter) {
            // Observe the sent time immediately before performing I/O.
            if (ping != null) ping.send();
            spdyWriter.ping(0, id);
        }
    }

    private synchronized Ping removePing(int id) {
        return pings != null ? pings.remove(id) : null;
    }

    /**
     * Sends a noop frame to the peer.
     */
    public void noop() throws IOException {
        spdyWriter.noop();
    }

    public void flush() throws IOException {
        synchronized (spdyWriter) {
            spdyWriter.out.flush();
        }
    }

    private void shutdownLater(final int statusCode) {
        writeExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    shutdown(statusCode);
                } catch (IOException ignored) {
                }
            }
        });
    }

    /**
     * Degrades this connection such that new streams can neither be created
     * locally, nor accepted from the remote peer. Existing streams are not
     * impacted. This is intended to permit an endpoint to gracefully stop
     * accepting new requests without harming previously established streams.
     *
     * @param statusCode one of {@link #GOAWAY_OK}, {@link
     *     #GOAWAY_INTERNAL_ERROR} or {@link #GOAWAY_PROTOCOL_ERROR}.
     */
    public void shutdown(int statusCode) throws IOException {
        synchronized (spdyWriter) {
            int lastGoodStreamId;
            synchronized (this) {
                if (shutdown) {
                    return;
                }
                shutdown = true;
                lastGoodStreamId = this.lastGoodStreamId;
            }
            spdyWriter.goAway(0, lastGoodStreamId, statusCode);
        }
    }

    /**
     * Closes this connection. This cancels all open streams and unanswered
     * pings. It closes the underlying input and output streams and shuts down
     * internal executor services.
     */
    @Override public void close() throws IOException {
        shutdown(GOAWAY_OK);

        SpdyStream[] streamsToClose = null;
        Ping[] pingsToCancel = null;
        synchronized (this) {
            if (!streams.isEmpty()) {
                streamsToClose = streams.values().toArray(new SpdyStream[streams.size()]);
                streams.clear();
            }
            if (pings != null) {
                pingsToCancel = pings.values().toArray(new Ping[pings.size()]);
                pings = null;
            }
        }

        if (streamsToClose != null) {
            for (SpdyStream stream : streamsToClose) {
                try {
                    stream.close(SpdyStream.RST_CANCEL);
                } catch (Throwable ignored) {
                }
            }
        }

        if (pingsToCancel != null) {
            for (Ping ping : pingsToCancel) {
                ping.cancel();
            }
        }

        writeExecutor.shutdown();
        callbackExecutor.shutdown();
        readExecutor.shutdown();
        Util.closeAll(spdyReader, spdyWriter);
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

    private class Reader implements Runnable, SpdyReader.Handler {
        @Override public void run() {
            try {
                while (spdyReader.nextFrame(this)) {
                }
            } catch (ProtocolException e) {
                shutdownLater(GOAWAY_PROTOCOL_ERROR);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                Util.closeQuietly(SpdyConnection.this);
            }
        }

        @Override public void data(int flags, int streamId, InputStream in, int length)
                throws IOException {
            SpdyStream dataStream = getStream(streamId);
            if (dataStream == null) {
                writeSynResetLater(streamId, SpdyStream.RST_INVALID_STREAM);
                Util.skipByReading(in, length);
                return;
            }
            try {
                dataStream.receiveData(in, length);
                if ((flags & SpdyConnection.FLAG_FIN) != 0) {
                    dataStream.receiveFin();
                }
            } catch (ProtocolException e) {
                Util.skipByReading(in, length);
                dataStream.closeLater(SpdyStream.RST_FLOW_CONTROL_ERROR);
            }
        }

        @Override public void synStream(int flags, int streamId, int associatedStreamId,
                int priority, int slot, List<String> nameValueBlock) {
            final SpdyStream synStream = new SpdyStream(streamId, SpdyConnection.this,
                    flags, priority, slot, nameValueBlock);
            final SpdyStream previous;
            synchronized (SpdyConnection.this) {
                if (shutdown) {
                    return;
                }
                lastGoodStreamId = streamId;
                previous = streams.put(streamId, synStream);
            }
            if (previous != null) {
                previous.closeLater(SpdyStream.RST_PROTOCOL_ERROR);
                removeStream(streamId);
                return;
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
        }

        @Override public void synReply(int flags, int streamId, List<String> nameValueBlock)
                throws IOException {
            SpdyStream replyStream = getStream(streamId);
            if (replyStream == null) {
                writeSynResetLater(streamId, SpdyStream.RST_INVALID_STREAM);
                return;
            }
            try {
                replyStream.receiveReply(nameValueBlock);
                if ((flags & SpdyConnection.FLAG_FIN) != 0) {
                    replyStream.receiveFin();
                }
            } catch (ProtocolException e) {
                replyStream.closeLater(SpdyStream.RST_STREAM_IN_USE);
            }
        }

        @Override public void headers(int flags, int streamId, List<String> nameValueBlock)
                throws IOException {
            SpdyStream replyStream = getStream(streamId);
            if (replyStream != null) {
                try {
                    replyStream.receiveHeaders(nameValueBlock);
                } catch (ProtocolException e) {
                    replyStream.closeLater(SpdyStream.RST_PROTOCOL_ERROR);
                }
            }
        }

        @Override public void rstStream(int flags, int streamId, int statusCode) {
            SpdyStream rstStream = removeStream(streamId);
            if (rstStream != null) {
                rstStream.receiveRstStream(statusCode);
            }
        }

        @Override public void settings(int flags, Settings newSettings) {
            synchronized (SpdyConnection.this) {
                if (settings == null
                        || (flags & Settings.FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS) != 0) {
                    settings = newSettings;
                } else {
                    settings.merge(newSettings);
                }
            }
        }

        @Override public void noop() {
        }

        @Override public void ping(int flags, int streamId) {
            if (client != (streamId % 2 == 1)) {
                // Respond to a client ping if this is a server and vice versa.
                writePingLater(streamId, null);
            } else {
                Ping ping = removePing(streamId);
                if (ping != null) {
                    ping.receive();
                }
            }
        }

        @Override public void goAway(int flags, int lastGoodStreamId, int statusCode) {
            synchronized (SpdyConnection.this) {
                shutdown = true;

                // Fail all streams created after the last good stream ID.
                for (Iterator<Map.Entry<Integer, SpdyStream>> i = streams.entrySet().iterator();
                        i.hasNext();) {
                    Map.Entry<Integer, SpdyStream> entry = i.next();
                    int streamId = entry.getKey();
                    if (streamId > lastGoodStreamId && entry.getValue().isLocallyInitiated()) {
                        entry.getValue().receiveRstStream(SpdyStream.RST_REFUSED_STREAM);
                        i.remove();
                    }
                }
            }
        }

        @Override public void windowUpdate(int flags, int streamId, int deltaWindowSize) {
            // TODO
        }
    }
}
