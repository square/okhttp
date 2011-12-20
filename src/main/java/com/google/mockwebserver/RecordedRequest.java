/*
 * Copyright (C) 2011 Google Inc.
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

package com.google.mockwebserver;

import java.net.Socket;
import java.util.List;
import javax.net.ssl.SSLSocket;

/**
 * An HTTP request that came into the mock web server.
 */
public final class RecordedRequest {
    private final String requestLine;
    private final List<String> headers;
    private final List<Integer> chunkSizes;
    private final int bodySize;
    private final byte[] body;
    private final int sequenceNumber;
    private final String sslProtocol;

    RecordedRequest(String requestLine, List<String> headers, List<Integer> chunkSizes,
            int bodySize, byte[] body, int sequenceNumber, Socket socket) {
        this.requestLine = requestLine;
        this.headers = headers;
        this.chunkSizes = chunkSizes;
        this.bodySize = bodySize;
        this.body = body;
        this.sequenceNumber = sequenceNumber;

        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            sslProtocol = sslSocket.getSession().getProtocol();
        } else {
            sslProtocol = null;
        }
    }

    public String getRequestLine() {
        return requestLine;
    }

    public List<String> getHeaders() {
        return headers;
    }

    /**
     * Returns the sizes of the chunks of this request's body, or an empty list
     * if the request's body was empty or unchunked.
     */
    public List<Integer> getChunkSizes() {
        return chunkSizes;
    }

    /**
     * Returns the total size of the body of this POST request (before
     * truncation).
     */
    public int getBodySize() {
        return bodySize;
    }

    /**
     * Returns the body of this POST request. This may be truncated.
     */
    public byte[] getBody() {
        return body;
    }

    /**
     * Returns the index of this request on its HTTP connection. Since a single
     * HTTP connection may serve multiple requests, each request is assigned its
     * own sequence number.
     */
    public int getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Returns the connection's SSL protocol like {@code TLSv1}, {@code SSLv3},
     * {@code NONE} or null if the connection doesn't use SSL.
     */
    public String getSslProtocol() {
        return sslProtocol;
    }

    @Override public String toString() {
        return requestLine;
    }
}
