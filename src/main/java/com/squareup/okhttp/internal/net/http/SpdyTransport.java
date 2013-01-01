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

package com.squareup.okhttp.internal.net.http;

import com.squareup.okhttp.internal.net.spdy.SpdyConnection;
import com.squareup.okhttp.internal.net.spdy.SpdyStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.util.List;

public final class SpdyTransport implements Transport {
    private final HttpEngine httpEngine;
    private final SpdyConnection spdyConnection;
    private SpdyStream stream;

    // TODO: set sentMillis
    // TODO: set cookie stuff

    public SpdyTransport(HttpEngine httpEngine, SpdyConnection spdyConnection) {
        this.httpEngine = httpEngine;
        this.spdyConnection = spdyConnection;
    }

    @Override public OutputStream createRequestBody() throws IOException {
        // TODO: if we aren't streaming up to the server, we should buffer the whole request
        writeRequestHeaders();
        return stream.getOutputStream();
    }

    @Override public void writeRequestHeaders() throws IOException {
        if (stream != null) {
            return;
        }
        RawHeaders requestHeaders = httpEngine.requestHeaders.getHeaders();
        String version = httpEngine.connection.getHttpMinorVersion() == 1 ? "HTTP/1.1" : "HTTP/1.0";
        requestHeaders.addSpdyRequestHeaders(httpEngine.method, httpEngine.uri.getScheme(),
                HttpEngine.requestPath(httpEngine.policy.getURL()), version);
        boolean hasRequestBody = httpEngine.hasRequestBody();
        boolean hasResponseBody = true;
        stream = spdyConnection.newStream(requestHeaders.toNameValueBlock(),
                hasRequestBody, hasResponseBody);
        stream.setReadTimeout(httpEngine.policy.getReadTimeout());
    }

    @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override public void flushRequest() throws IOException {
        stream.getOutputStream().close();
    }

    @Override public ResponseHeaders readResponseHeaders() throws IOException {
        // TODO: fix the SPDY implementation so this throws a (buffered) IOException
        List<String> nameValueBlock = stream.getResponseHeaders();
        RawHeaders rawHeaders = RawHeaders.fromNameValueBlock(nameValueBlock);
        rawHeaders.computeResponseStatusLineFromSpdyHeaders();
        return new ResponseHeaders(httpEngine.uri, rawHeaders);
    }

    @Override public InputStream getTransferStream(CacheRequest cacheRequest) throws IOException {
        // TODO: handle HTTP responses that don't have a response body
        return stream.getInputStream();
    }

    @Override public boolean makeReusable(OutputStream requestBodyOut, InputStream responseBodyIn) {
        return true;
    }
}
