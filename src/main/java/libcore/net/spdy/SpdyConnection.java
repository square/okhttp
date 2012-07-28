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

    static final int FLAG_FIN = 0x01;
    static final int FLAG_UNIDIRECTIONAL = 0x02;

    static final int TYPE_EOF = -1;
    static final int TYPE_DATA = 0x00;
    static final int TYPE_SYN_STREAM = 0x01;
    static final int TYPE_SYN_REPLY = 0x02;
    static final int TYPE_RST_STREAM = 0x03;
    static final int TYPE_SETTINGS = 0x04;
    static final int TYPE_NOOP = 0x05;
    static final int TYPE_PING = 0x06;
    static final int TYPE_GOAWAY = 0x07;
    static final int TYPE_HEADERS = 0x08;
    static final int VERSION = 2;

    /** Guarded by this. */
    private int nextStreamId;
    private final SpdyReader spdyReader;
    private final SpdyWriter spdyWriter;
    private final Executor executor;

    /**
     * User code to run in response to an incoming stream. This must not be run
     * on the read thread, otherwise a deadlock is possible.
     */
    private final IncomingStreamHandler handler;

    private final Map<Integer, SpdyStream> streams = Collections.synchronizedMap(
            new HashMap<Integer, SpdyStream>());

    private SpdyConnection(Builder builder) {
        nextStreamId = builder.client ? 1 : 2;
        spdyReader = new SpdyReader(builder.in);
        spdyWriter = new SpdyWriter(builder.out);
        handler = builder.handler;

        String name = isClient() ? "ClientReader" : "ServerReader";
        executor = builder.executor != null
                ? builder.executor
                : Executors.newCachedThreadPool(Threads.newThreadFactory(name));
        executor.execute(new Reader());
    }

    /**
     * Returns true if this peer initiated the connection.
     */
    public boolean isClient() {
        return nextStreamId % 2 == 1;
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
        spdyWriter.streamId = streamId;
        spdyWriter.associatedStreamId = associatedStreamId;
        spdyWriter.priority = priority;
        spdyWriter.nameValueBlock = requestHeaders;
        spdyWriter.synStream();

        return result;
    }

    synchronized void writeSynReply(int streamId, List<String> alternating) throws IOException {
        int flags = 0; // TODO
        spdyWriter.flags = flags;
        spdyWriter.streamId = streamId;
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
        spdyWriter.streamId = streamId;
        spdyWriter.statusCode = statusCode;
        spdyWriter.synReset();
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

        private boolean readFrame() throws IOException {
            switch (spdyReader.nextFrame()) {
            case TYPE_EOF:
                return false;

            case TYPE_DATA:
                getStream(spdyReader.streamId)
                        .receiveData(spdyReader.in, spdyReader.flags, spdyReader.length);
                return true;

            case TYPE_SYN_STREAM:
                final SpdyStream stream = new SpdyStream(spdyReader.streamId, SpdyConnection.this,
                        spdyReader.nameValueBlock, spdyReader.flags);
                SpdyStream previous = streams.put(spdyReader.streamId, stream);
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
                getStream(spdyReader.streamId).receiveReply(spdyReader.nameValueBlock);
                return true;

            case TYPE_RST_STREAM:
                getStream(spdyReader.streamId).receiveRstStream(spdyReader.statusCode);
                return true;

            case SpdyConnection.TYPE_SETTINGS:
                // TODO: implement
                System.out.println("Unimplemented TYPE_SETTINGS frame discarded");
                return true;

            case SpdyConnection.TYPE_NOOP:
            case SpdyConnection.TYPE_PING:
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
