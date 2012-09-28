/*
 * Copyright (C) 2012 The Android Open Source Project
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

package libcore.net.http;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CookieHandler;
import java.net.URL;
import libcore.io.Streams;
import libcore.util.Libcore;

final class HttpTransport implements Transport {
    /**
     * The maximum number of bytes to buffer when sending headers and a request
     * body. When the headers and body can be sent in a single write, the
     * request completes sooner. In one WiFi benchmark, using a large enough
     * buffer sped up some uploads by half.
     */
    private static final int MAX_REQUEST_BUFFER_LENGTH = 32768;

    private final HttpEngine httpEngine;
    private final InputStream socketIn;
    private final OutputStream socketOut;

    /**
     * This stream buffers the request headers and the request body when their
     * combined size is less than MAX_REQUEST_BUFFER_LENGTH. By combining them
     * we can save socket writes, which in turn saves a packet transmission.
     * This is socketOut if the request size is large or unknown.
     */
    private OutputStream requestOut;

    public HttpTransport(HttpEngine httpEngine,
            OutputStream outputStream, InputStream inputStream) {
        this.httpEngine = httpEngine;
        this.socketOut = outputStream;
        this.requestOut = outputStream;
        this.socketIn = inputStream;
    }

    @Override public OutputStream createRequestBody() throws IOException {
        boolean chunked = httpEngine.requestHeaders.isChunked();
        if (!chunked
                && httpEngine.policy.getChunkLength() > 0
                && httpEngine.connection.httpMinorVersion != 0) {
            httpEngine.requestHeaders.setChunked();
            chunked = true;
        }

        // Stream a request body of unknown length.
        if (chunked) {
            int chunkLength = httpEngine.policy.getChunkLength();
            if (chunkLength == -1) {
                chunkLength = HttpEngine.DEFAULT_CHUNK_LENGTH;
            }
            writeRequestHeaders();
            return new ChunkedOutputStream(requestOut, chunkLength);
        }

        // Stream a request body of a known length.
        int fixedContentLength = httpEngine.policy.getFixedContentLength();
        if (fixedContentLength != -1) {
            httpEngine.requestHeaders.setContentLength(fixedContentLength);
            writeRequestHeaders();
            return new FixedLengthOutputStream(requestOut, fixedContentLength);
        }

        // Buffer a request body of a known length.
        int contentLength = httpEngine.requestHeaders.getContentLength();
        if (contentLength != -1) {
            writeRequestHeaders();
            return new RetryableOutputStream(contentLength);
        }

        // Buffer a request body of an unknown length. Don't write request
        // headers until the entire body is ready; otherwise we can't set the
        // Content-Length header correctly.
        return new RetryableOutputStream();
    }

    @Override public void flushRequest() throws IOException {
        requestOut.flush();
        requestOut = socketOut;
    }

    @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
        requestBody.writeToSocket(requestOut);
    }

    /**
     * Prepares the HTTP headers and sends them to the server.
     *
     * <p>For streaming requests with a body, headers must be prepared
     * <strong>before</strong> the output stream has been written to. Otherwise
     * the body would need to be buffered!
     *
     * <p>For non-streaming requests with a body, headers must be prepared
     * <strong>after</strong> the output stream has been written to and closed.
     * This ensures that the {@code Content-Length} header field receives the
     * proper value.
     */
    public void writeRequestHeaders() throws IOException {
        if (httpEngine.sentRequestMillis != -1) {
            throw new IllegalStateException();
        }
        httpEngine.sentRequestMillis = System.currentTimeMillis();

        int contentLength = httpEngine.requestHeaders.getContentLength();
        RawHeaders headersToSend = getNetworkRequestHeaders();
        byte[] bytes = headersToSend.toRequestHeader().getBytes("ISO-8859-1");

        if (contentLength != -1 && bytes.length + contentLength <= MAX_REQUEST_BUFFER_LENGTH) {
            requestOut = new BufferedOutputStream(socketOut, bytes.length + contentLength);
        }

        requestOut.write(bytes);
    }

    private RawHeaders getNetworkRequestHeaders() {
        return httpEngine.method == HttpEngine.CONNECT
                ? getTunnelNetworkRequestHeaders()
                : httpEngine.requestHeaders.getHeaders();
    }

    /**
     * If we're establishing an HTTPS tunnel with CONNECT (RFC 2817 5.2), send
     * only the minimum set of headers. This avoids sending potentially
     * sensitive data like HTTP cookies to the proxy unencrypted.
     */
    private RawHeaders getTunnelNetworkRequestHeaders() {
        RequestHeaders privateHeaders = httpEngine.requestHeaders;
        URL url = httpEngine.policy.getURL();

        RawHeaders result = new RawHeaders();
        result.setRequestLine("CONNECT " + url.getHost() + ":" + Libcore.getEffectivePort(url)
                + " HTTP/1.1");

        // Always set Host and User-Agent.
        String host = privateHeaders.getHost();
        if (host == null) {
            host = httpEngine.getOriginAddress(url);
        }
        result.set("Host", host);

        String userAgent = privateHeaders.getUserAgent();
        if (userAgent == null) {
            userAgent = httpEngine.getDefaultUserAgent();
        }
        result.set("User-Agent", userAgent);

        // Copy over the Proxy-Authorization header if it exists.
        String proxyAuthorization = privateHeaders.getProxyAuthorization();
        if (proxyAuthorization != null) {
            result.set("Proxy-Authorization", proxyAuthorization);
        }

        // Always set the Proxy-Connection to Keep-Alive for the benefit of
        // HTTP/1.0 proxies like Squid.
        result.set("Proxy-Connection", "Keep-Alive");
        return result;
    }

    @Override public ResponseHeaders readResponseHeaders() throws IOException {
        RawHeaders headers;
        do {
            headers = new RawHeaders();
            headers.setStatusLine(Streams.readAsciiLine(socketIn));
            httpEngine.connection.httpMinorVersion = headers.getHttpMinorVersion();
            readHeaders(headers);
        } while (headers.getResponseCode() == HttpEngine.HTTP_CONTINUE);
        return new ResponseHeaders(httpEngine.uri, headers);
    }

    /**
     * Reads headers or trailers and updates the cookie store.
     */
    private void readHeaders(RawHeaders headers) throws IOException {
        // parse the result headers until the first blank line
        String line;
        while ((line = Streams.readAsciiLine(socketIn)).length() != 0) {
            headers.addLine(line);
        }

        CookieHandler cookieHandler = CookieHandler.getDefault();
        if (cookieHandler != null) {
            cookieHandler.put(httpEngine.uri, headers.toMultimap(true));
        }
    }

    public boolean makeReusable(OutputStream requestBodyOut, InputStream responseBodyIn) {
        // We cannot reuse sockets that have incomplete output.
        if (requestBodyOut != null && !((AbstractHttpOutputStream) requestBodyOut).closed) {
            return false;
        }

        // If the request specified that the connection shouldn't be reused,
        // don't reuse it. This advice doesn't apply to CONNECT requests because
        // the "Connection: close" header goes the origin server, not the proxy.
        if (httpEngine.requestHeaders.hasConnectionClose()
                && httpEngine.method != HttpEngine.CONNECT) {
            return false;
        }

        // If the response specified that the connection shouldn't be reused, don't reuse it.
        if (httpEngine.responseHeaders != null && httpEngine.responseHeaders.hasConnectionClose()) {
            return false;
        }

        if (responseBodyIn instanceof UnknownLengthHttpInputStream) {
            return false;
        }

        if (responseBodyIn != null) {
            // Discard the response body before the connection can be reused.
            try {
                Streams.skipAll(responseBodyIn);
            } catch (IOException e) {
                return false;
            }
        }

        return true;
    }

    @Override public InputStream getTransferStream(CacheRequest cacheRequest) throws IOException {
        if (!httpEngine.hasResponseBody()) {
            return new FixedLengthInputStream(socketIn, cacheRequest, httpEngine, 0);
        }

        if (httpEngine.responseHeaders.isChunked()) {
            return new ChunkedInputStream(socketIn, cacheRequest, this);
        }

        if (httpEngine.responseHeaders.getContentLength() != -1) {
            return new FixedLengthInputStream(socketIn, cacheRequest, httpEngine,
                    httpEngine.responseHeaders.getContentLength());
        }

        /*
         * Wrap the input stream from the HttpConnection (rather than
         * just returning "socketIn" directly here), so that we can control
         * its use after the reference escapes.
         */
        return new UnknownLengthHttpInputStream(socketIn, cacheRequest, httpEngine);
    }

    /**
     * An HTTP body with a fixed length known in advance.
     */
    private static final class FixedLengthOutputStream extends AbstractHttpOutputStream {
        private final OutputStream socketOut;
        private int bytesRemaining;

        private FixedLengthOutputStream(OutputStream socketOut, int bytesRemaining) {
            this.socketOut = socketOut;
            this.bytesRemaining = bytesRemaining;
        }

        @Override public void write(byte[] buffer, int offset, int count) throws IOException {
            checkNotClosed();
            Libcore.checkOffsetAndCount(buffer.length, offset, count);
            if (count > bytesRemaining) {
                throw new IOException("expected " + bytesRemaining
                        + " bytes but received " + count);
            }
            socketOut.write(buffer, offset, count);
            bytesRemaining -= count;
        }

        @Override public void flush() throws IOException {
            if (closed) {
                return; // don't throw; this stream might have been closed on the caller's behalf
            }
            socketOut.flush();
        }

        @Override public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (bytesRemaining > 0) {
                throw new IOException("unexpected end of stream");
            }
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies. Chunks are
     * buffered until {@code maxChunkLength} bytes are ready, at which point the
     * chunk is written and the buffer is cleared.
     */
    private static final class ChunkedOutputStream extends AbstractHttpOutputStream {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] HEX_DIGITS = {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        };
        private static final byte[] FINAL_CHUNK = new byte[] {'0', '\r', '\n', '\r', '\n'};

        /** Scratch space for up to 8 hex digits, and then a constant CRLF. */
        private final byte[] hex = {0, 0, 0, 0, 0, 0, 0, 0, '\r', '\n'};

        private final OutputStream socketOut;
        private final int maxChunkLength;
        private final ByteArrayOutputStream bufferedChunk;

        private ChunkedOutputStream(OutputStream socketOut, int maxChunkLength) {
            this.socketOut = socketOut;
            this.maxChunkLength = Math.max(1, dataLength(maxChunkLength));
            this.bufferedChunk = new ByteArrayOutputStream(maxChunkLength);
        }

        /**
         * Returns the amount of data that can be transmitted in a chunk whose total
         * length (data+headers) is {@code dataPlusHeaderLength}. This is presumably
         * useful to match sizes with wire-protocol packets.
         */
        private int dataLength(int dataPlusHeaderLength) {
            int headerLength = 4; // "\r\n" after the size plus another "\r\n" after the data
            for (int i = dataPlusHeaderLength - headerLength; i > 0; i >>= 4) {
                headerLength++;
            }
            return dataPlusHeaderLength - headerLength;
        }

        @Override public synchronized void write(byte[] buffer, int offset, int count)
                throws IOException {
            checkNotClosed();
            Libcore.checkOffsetAndCount(buffer.length, offset, count);

            while (count > 0) {
                int numBytesWritten;

                if (bufferedChunk.size() > 0 || count < maxChunkLength) {
                    // fill the buffered chunk and then maybe write that to the stream
                    numBytesWritten = Math.min(count, maxChunkLength - bufferedChunk.size());
                    // TODO: skip unnecessary copies from buffer->bufferedChunk?
                    bufferedChunk.write(buffer, offset, numBytesWritten);
                    if (bufferedChunk.size() == maxChunkLength) {
                        writeBufferedChunkToSocket();
                    }

                } else {
                    // write a single chunk of size maxChunkLength to the stream
                    numBytesWritten = maxChunkLength;
                    writeHex(numBytesWritten);
                    socketOut.write(buffer, offset, numBytesWritten);
                    socketOut.write(CRLF);
                }

                offset += numBytesWritten;
                count -= numBytesWritten;
            }
        }

        /**
         * Equivalent to, but cheaper than writing Integer.toHexString().getBytes()
         * followed by CRLF.
         */
        private void writeHex(int i) throws IOException {
            int cursor = 8;
            do {
                hex[--cursor] = HEX_DIGITS[i & 0xf];
            } while ((i >>>= 4) != 0);
            socketOut.write(hex, cursor, hex.length - cursor);
        }

        @Override public synchronized void flush() throws IOException {
            if (closed) {
                return; // don't throw; this stream might have been closed on the caller's behalf
            }
            writeBufferedChunkToSocket();
            socketOut.flush();
        }

        @Override public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            writeBufferedChunkToSocket();
            socketOut.write(FINAL_CHUNK);
        }

        private void writeBufferedChunkToSocket() throws IOException {
            int size = bufferedChunk.size();
            if (size <= 0) {
                return;
            }

            writeHex(size);
            bufferedChunk.writeTo(socketOut);
            bufferedChunk.reset();
            socketOut.write(CRLF);
        }
    }

    /**
     * An HTTP body with a fixed length specified in advance.
     */
    private static class FixedLengthInputStream extends AbstractHttpInputStream {
        private int bytesRemaining;

        public FixedLengthInputStream(InputStream is, CacheRequest cacheRequest,
                HttpEngine httpEngine, int length) throws IOException {
            super(is, httpEngine, cacheRequest);
            bytesRemaining = length;
            if (bytesRemaining == 0) {
                endOfInput(true);
            }
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            Libcore.checkOffsetAndCount(buffer.length, offset, count);
            checkNotClosed();
            if (bytesRemaining == 0) {
                return -1;
            }
            int read = in.read(buffer, offset, Math.min(count, bytesRemaining));
            if (read == -1) {
                unexpectedEndOfInput(); // the server didn't supply the promised content length
                throw new IOException("unexpected end of stream");
            }
            bytesRemaining -= read;
            cacheWrite(buffer, offset, read);
            if (bytesRemaining == 0) {
                endOfInput(true);
            }
            return read;
        }

        @Override public int available() throws IOException {
            checkNotClosed();
            return bytesRemaining == 0 ? 0 : Math.min(in.available(), bytesRemaining);
        }

        @Override public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (bytesRemaining != 0) {
                unexpectedEndOfInput();
            }
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies.
     */
    private static class ChunkedInputStream extends AbstractHttpInputStream {
        private static final int MIN_LAST_CHUNK_LENGTH = "\r\n0\r\n\r\n".length();
        private static final int NO_CHUNK_YET = -1;
        private final HttpTransport transport;
        private int bytesRemainingInChunk = NO_CHUNK_YET;
        private boolean hasMoreChunks = true;

        ChunkedInputStream(InputStream is, CacheRequest cacheRequest,
                HttpTransport transport) throws IOException {
            super(is, transport.httpEngine, cacheRequest);
            this.transport = transport;
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            Libcore.checkOffsetAndCount(buffer.length, offset, count);
            checkNotClosed();

            if (!hasMoreChunks) {
                return -1;
            }
            if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize();
                if (!hasMoreChunks) {
                    return -1;
                }
            }
            int read = in.read(buffer, offset, Math.min(count, bytesRemainingInChunk));
            if (read == -1) {
                unexpectedEndOfInput(); // the server didn't supply the promised chunk length
                throw new IOException("unexpected end of stream");
            }
            bytesRemainingInChunk -= read;
            cacheWrite(buffer, offset, read);

            /*
            * If we're at the end of a chunk and the next chunk size is readable,
            * read it! Reading the last chunk causes the underlying connection to
            * be recycled and we want to do that as early as possible. Otherwise
            * self-delimiting streams like gzip will never be recycled.
            * http://code.google.com/p/android/issues/detail?id=7059
            */
            if (bytesRemainingInChunk == 0 && in.available() >= MIN_LAST_CHUNK_LENGTH) {
                readChunkSize();
            }

            return read;
        }

        private void readChunkSize() throws IOException {
            // read the suffix of the previous chunk
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                Streams.readAsciiLine(in);
            }
            String chunkSizeString = Streams.readAsciiLine(in);
            int index = chunkSizeString.indexOf(";");
            if (index != -1) {
                chunkSizeString = chunkSizeString.substring(0, index);
            }
            try {
                bytesRemainingInChunk = Integer.parseInt(chunkSizeString.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("Expected a hex chunk size, but was " + chunkSizeString);
            }
            if (bytesRemainingInChunk == 0) {
                hasMoreChunks = false;
                transport.readHeaders(httpEngine.responseHeaders.getHeaders());
                endOfInput(true);
            }
        }

        @Override public int available() throws IOException {
            checkNotClosed();
            if (!hasMoreChunks || bytesRemainingInChunk == NO_CHUNK_YET) {
                return 0;
            }
            return Math.min(in.available(), bytesRemainingInChunk);
        }

        @Override public void close() throws IOException {
            if (closed) {
                return;
            }

            closed = true;
            if (hasMoreChunks) {
                unexpectedEndOfInput();
            }
        }
    }

    /**
     * An HTTP payload terminated by the end of the socket stream.
     */
    private static final class UnknownLengthHttpInputStream extends AbstractHttpInputStream {
        private boolean inputExhausted;

        private UnknownLengthHttpInputStream(InputStream is, CacheRequest cacheRequest,
                HttpEngine httpEngine) throws IOException {
            super(is, httpEngine, cacheRequest);
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            Libcore.checkOffsetAndCount(buffer.length, offset, count);
            checkNotClosed();
            if (in == null || inputExhausted) {
                return -1;
            }
            int read = in.read(buffer, offset, count);
            if (read == -1) {
                inputExhausted = true;
                endOfInput(false);
                return -1;
            }
            cacheWrite(buffer, offset, read);
            return read;
        }

        @Override public int available() throws IOException {
            checkNotClosed();
            return in == null ? 0 : in.available();
        }

        @Override public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (!inputExhausted) {
                unexpectedEndOfInput();
            }
        }
    }
}
