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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;
import libcore.io.Streams;
import libcore.util.Libcore;

import static java.nio.ByteOrder.BIG_ENDIAN;

/**
 * A logical bidirectional stream.
 */
public final class SpdyStream {

    /*
     * Internal state is guarded by this. No long-running or potentially
     * blocking operations are performed while the lock is held.
     */

    private static final int DATA_FRAME_HEADER_LENGTH = 8;

    public static final int RST_PROTOCOL_ERROR = 1;
    public static final int RST_INVALID_STREAM = 2;
    public static final int RST_REFUSED_STREAM = 3;
    public static final int RST_UNSUPPORTED_VERSION = 4;
    public static final int RST_CANCEL = 5;
    public static final int RST_INTERNAL_ERROR = 6;
    public static final int RST_FLOW_CONTROL_ERROR = 7;

    private final int id;
    private final SpdyConnection connection;

    /** Headers sent by the stream initiator. Immutable and non null. */
    private final List<String> requestHeaders;

    /** Headers sent in the stream reply. Null if reply is either not sent or not sent yet. */
    private List<String> responseHeaders;

    private final SpdyDataInputStream in = new SpdyDataInputStream();
    private final SpdyDataOutputStream out = new SpdyDataOutputStream();

    /**
     * The reason why this stream was abnormally closed. If there are multiple
     * reasons to abnormally close this stream (such as both peers closing it
     * near-simultaneously) then this is the first reason known to this peer.
     */
    private int rstStatusCode = -1;

    /**
     * True if either side has shut down the input stream. We will receive no
     * more bytes beyond those already in the buffer. Guarded by this.
     */
    private boolean inFinished;

    /**
     * True if either side has shut down the output stream. We will write no
     * more bytes to the output stream. Guarded by this.
     */
    private boolean outFinished;

    SpdyStream(int id, SpdyConnection connection, List<String> requestHeaders, int flags) {
        this.id = id;
        this.connection = connection;
        this.requestHeaders = requestHeaders;

        if (isLocallyInitiated()) {
            // I am the sender
            inFinished = (flags & SpdyConnection.FLAG_UNIDIRECTIONAL) != 0;
            outFinished = (flags & SpdyConnection.FLAG_FIN) != 0;
        } else {
            // I am the receiver
            inFinished = (flags & SpdyConnection.FLAG_FIN) != 0;
            outFinished = (flags & SpdyConnection.FLAG_UNIDIRECTIONAL) != 0;
        }
    }

    /**
     * Returns true if this stream was created by this peer.
     */
    public boolean isLocallyInitiated() {
        boolean streamIsClient = (id % 2 == 1);
        return connection.isClient() == streamIsClient;
    }

    public SpdyConnection getConnection() {
        return connection;
    }

    public List<String> getRequestHeaders() {
        return requestHeaders;
    }

    public synchronized List<String> getResponseHeaders() throws InterruptedException {
        while (responseHeaders == null && rstStatusCode == -1) {
            wait();
        }
        return responseHeaders;
    }

    /**
     * Returns the reason why this stream was closed, or -1 if it closed
     * normally or has not yet been closed. Valid reasons are {@link
     * #RST_PROTOCOL_ERROR}, {@link #RST_INVALID_STREAM}, {@link
     * #RST_REFUSED_STREAM}, {@link #RST_UNSUPPORTED_VERSION}, {@link
     * #RST_CANCEL}, {@link #RST_INTERNAL_ERROR} and {@link
     * #RST_FLOW_CONTROL_ERROR}.
     */
    public synchronized int getRstStatusCode() {
        return rstStatusCode;
    }

    public InputStream getInputStream() {
        return in;
    }

    public OutputStream getOutputStream() {
        if (!isLocallyInitiated()) {
            throw new IllegalStateException("use reply for a remotely initiated stream");
        }
        return out;
    }

    /**
     * Sends a reply with 0 or more bytes of data to follow, which should be
     * written to the returned output stream.
     */
    public OutputStream reply(List<String> responseHeaders) throws IOException {
        reply(responseHeaders, 0);
        return out;
    }

    /**
     * Sends a reply with 0 bytes to follow.
     */
    public void replyNoContent(List<String> responseHeaders) throws IOException {
        reply(responseHeaders, SpdyConnection.FLAG_FIN);
        outFinished = true;
    }

    private void reply(List<String> responseHeaders, int flags) throws IOException {
        if (responseHeaders == null) throw new NullPointerException("responseHeaders == null");
        if (isLocallyInitiated()) {
            throw new IllegalStateException("cannot reply to a locally initiated stream");
        }
        synchronized (this) {
            if (this.responseHeaders != null) {
                throw new IllegalStateException("reply already sent");
            }
            this.responseHeaders = responseHeaders;
        }
        connection.writeSynReply(id, flags, responseHeaders);
    }

    /**
     * Abnormally terminate this stream.
     */
    public void close(int rstStatusCode) {
        synchronized (this) {
            // TODO: no-op if inFinished == true and outFinished == true ?
            if (this.rstStatusCode == -1) {
                return; // Already closed.
            }
            this.rstStatusCode = rstStatusCode;
            inFinished = true;
            outFinished = true;
            notifyAll();
            connection.writeSynResetLater(id, rstStatusCode);
        }
        connection.removeStream(id);
    }

    synchronized void receiveReply(List<String> strings) throws IOException {
        if (!isLocallyInitiated() || responseHeaders != null) {
            throw new IOException(); // TODO: send RST
        }
        responseHeaders = strings;
        notifyAll();
    }

    synchronized void receiveData(InputStream in, int flags, int length) throws IOException {
        this.in.receive(in, length);
        if ((flags & SpdyConnection.FLAG_FIN) != 0) {
            inFinished = true;
            notifyAll();
        }
    }

    synchronized void receiveRstStream(int statusCode) {
        if (rstStatusCode != -1) {
            rstStatusCode = statusCode;
            inFinished = true;
            outFinished = true;
            notifyAll();
        }
    }

    /**
     * An input stream that reads the incoming data frames of a stream. Although
     * this class uses synchronization to safely receive incoming data frames,
     * it is not intended for use by multiple readers.
     */
    private final class SpdyDataInputStream extends InputStream {
        /*
         * Store incoming data bytes in a circular buffer. When the buffer is
         * empty, pos == -1. Otherwise pos is the first byte to read and limit
         * is the first byte to write.
         *
         * { - - - X X X X - - - }
         *         ^       ^
         *        pos    limit
         *
         * { X X X - - - - X X X }
         *         ^       ^
         *       limit    pos
         */

        private final byte[] buffer = new byte[64 * 1024]; // 64KiB specified by TODO
        /** the next byte to be read, or -1 if the buffer is empty. Never buffer.length */
        private int pos = -1;
        /** the last byte to be read. Never buffer.length */
        private int limit;
        /** True if the caller has closed this stream. */
        private boolean closed;

        @Override public int available() throws IOException {
            synchronized (SpdyStream.this) {
                checkNotClosed();
                if (pos == -1) {
                    return 0;
                } else if (limit > pos) {
                    return limit - pos;
                } else {
                    return limit + (buffer.length - pos);
                }
            }
        }

        @Override public int read() throws IOException {
            return Streams.readSingleByte(this);
        }

        @Override public int read(byte[] b, int offset, int count) throws IOException {
            synchronized (SpdyStream.this) {
                checkNotClosed();
                Libcore.checkOffsetAndCount(b.length, offset, count);

                while (pos == -1 && !inFinished) {
                    try {
                        SpdyStream.this.wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }

                if (pos == -1) {
                    return -1;
                }

                int copied = 0;

                // drain from [pos..buffer.length)
                if (limit <= pos) {
                    int bytesToCopy = Math.min(count, buffer.length - pos);
                    System.arraycopy(buffer, pos, b, offset, bytesToCopy);
                    pos += bytesToCopy;
                    copied += bytesToCopy;
                    if (pos == buffer.length) {
                        pos = 0;
                    }
                }

                // drain from [pos..limit)
                if (copied < count) {
                    int bytesToCopy = Math.min(limit - pos, count - copied);
                    System.arraycopy(buffer, pos, b, offset + copied, bytesToCopy);
                    pos += bytesToCopy;
                    copied += bytesToCopy;
                }

                // TODO: notify peer of flow-control

                if (pos == limit) {
                    pos = -1;
                    limit = 0;
                }

                return copied;
            }
        }

        void receive(InputStream in, int byteCount) throws IOException {
            if (inFinished) {
                return; // ignore this; probably a benign race
            }
            if (byteCount == 0) {
                return;
            }

            if (byteCount > buffer.length - available()) {
                throw new IOException(); // TODO: RST the stream
            }

            // fill [limit..buffer.length)
            if (pos < limit) {
                int firstCopyCount = Math.min(byteCount, buffer.length - limit);
                Streams.readFully(in, buffer, limit, firstCopyCount);
                limit += firstCopyCount;
                byteCount -= firstCopyCount;
                if (limit == buffer.length) {
                    limit = 0;
                }
            }

            // fill [limit..pos)
            if (byteCount > 0) {
                Streams.readFully(in, buffer, limit, byteCount);
                limit += byteCount;
            }

            if (pos == -1) {
                pos = 0;
                SpdyStream.this.notifyAll();
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            // TODO: send RST to peer if !inFinished
        }

        private void checkNotClosed() throws IOException {
            if (closed) {
                throw new IOException("stream closed");
            }
        }
    }

    /**
     * An output stream that writes outgoing data frames of a stream. This class
     * is not thread safe.
     */
    private final class SpdyDataOutputStream extends OutputStream {
        private final byte[] buffer = new byte[8192];
        private int pos = DATA_FRAME_HEADER_LENGTH;

        /** True if the caller has closed this stream. */
        private boolean closed;

        @Override public void write(int b) throws IOException {
            Streams.writeSingleByte(this, b);
        }

        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            Libcore.checkOffsetAndCount(bytes.length, offset, count);
            checkNotClosed();

            while (count > 0) {
                if (pos == buffer.length) {
                    writeFrame(false);
                }
                int bytesToCopy = Math.min(count, buffer.length - pos);
                System.arraycopy(bytes, offset, buffer, pos, bytesToCopy);
                pos += bytesToCopy;
                offset += bytesToCopy;
                count -= bytesToCopy;
            }
        }

        @Override public void flush() throws IOException {
            checkNotClosed();
            if (pos > DATA_FRAME_HEADER_LENGTH) {
                writeFrame(false);
                connection.flush();
            }
        }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                writeFrame(true);
                connection.flush();
            }
        }

        private void writeFrame(boolean last) throws IOException {
            int flags = 0;
            if (last) {
                flags |= SpdyConnection.FLAG_FIN;
            }
            int length = pos - DATA_FRAME_HEADER_LENGTH;
            Libcore.pokeInt(buffer, 0, id & 0x7fffffff, BIG_ENDIAN);
            Libcore.pokeInt(buffer, 4, (flags & 0xff) << 24 | length & 0xffffff, BIG_ENDIAN);
            connection.writeFrame(buffer, 0, pos);
            pos = DATA_FRAME_HEADER_LENGTH;
        }

        private void checkNotClosed() throws IOException {
            synchronized (SpdyStream.this) {
                if (closed) {
                    throw new IOException("stream closed");
                }
                if (outFinished) {
                    throw new IOException("output stream finished "
                            + "(RST status code=" + rstStatusCode + ")");
                }
            }
        }
    }
}
