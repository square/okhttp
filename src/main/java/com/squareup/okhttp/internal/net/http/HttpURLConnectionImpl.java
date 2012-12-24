/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.squareup.okhttp.internal.net.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.internal.io.IoUtils;
import com.squareup.okhttp.internal.util.Libcore;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.SocketPermission;
import java.net.URL;
import java.security.Permission;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;

/**
 * This implementation uses HttpEngine to send requests and receive responses.
 * This class may use multiple HttpEngines to follow redirects, authentication
 * retries, etc. to retrieve the final response body.
 *
 * <h3>What does 'connected' mean?</h3>
 * This class inherits a {@code connected} field from the superclass. That field
 * is <strong>not</strong> used to indicate not whether this URLConnection is
 * currently connected. Instead, it indicates whether a connection has ever been
 * attempted. Once a connection has been attempted, certain properties (request
 * header fields, request method, etc.) are immutable. Test the {@code
 * connection} field on this class for null/non-null to determine of an instance
 * is currently connected to a server.
 */
public class HttpURLConnectionImpl extends HttpURLConnection {
    /**
     * HTTP 1.1 doesn't specify how many redirects to follow, but HTTP/1.0
     * recommended 5. http://www.w3.org/Protocols/HTTP/1.0/spec.html#Code3xx
     */
    private static final int MAX_REDIRECTS = 5;

    private final int defaultPort;

    private Proxy proxy;
    final ProxySelector proxySelector;
    final CookieHandler cookieHandler;
    final ResponseCache responseCache;
    final ConnectionPool connectionPool;

    private final RawHeaders rawRequestHeaders = new RawHeaders();

    private int redirectionCount;

    protected IOException httpEngineFailure;
    protected HttpEngine httpEngine;

    public HttpURLConnectionImpl(URL url, int defaultPort, Proxy proxy, ProxySelector proxySelector,
            CookieHandler cookieHandler, ResponseCache responseCache,
            ConnectionPool connectionPool) {
        super(url);
        this.defaultPort = defaultPort;
        this.proxy = proxy;
        this.proxySelector = proxySelector;
        this.cookieHandler = cookieHandler;
        this.responseCache = responseCache;
        this.connectionPool = connectionPool;
    }

    @Override public final void connect() throws IOException {
        initHttpEngine();
        boolean success;
        do {
            success = execute(false);
        } while (!success);
    }

    @Override public final void disconnect() {
        // Calling disconnect() before a connection exists should have no effect.
        if (httpEngine != null) {
            // We close the response body here instead of in
            // HttpEngine.release because that is called when input
            // has been completely read from the underlying socket.
            // However the response body can be a GZIPInputStream that
            // still has unread data.
            if (httpEngine.hasResponse()) {
                IoUtils.closeQuietly(httpEngine.getResponseBody());
            }
            httpEngine.release(false);
        }
    }

    /**
     * Returns an input stream from the server in the case of error such as the
     * requested file (txt, htm, html) is not found on the remote server.
     */
    @Override public final InputStream getErrorStream() {
        try {
            HttpEngine response = getResponse();
            if (response.hasResponseBody()
                    && response.getResponseCode() >= HTTP_BAD_REQUEST) {
                return response.getResponseBody();
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the value of the field at {@code position}. Returns null if there
     * are fewer than {@code position} headers.
     */
    @Override public final String getHeaderField(int position) {
        try {
            return getResponse().getResponseHeaders().getHeaders().getValue(position);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the value of the field corresponding to the {@code fieldName}, or
     * null if there is no such field. If the field has multiple values, the
     * last value is returned.
     */
    @Override public final String getHeaderField(String fieldName) {
        try {
            RawHeaders rawHeaders = getResponse().getResponseHeaders().getHeaders();
            return fieldName == null
                    ? rawHeaders.getStatusLine()
                    : rawHeaders.get(fieldName);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public final String getHeaderFieldKey(int position) {
        try {
            return getResponse().getResponseHeaders().getHeaders().getFieldName(position);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public final Map<String, List<String>> getHeaderFields() {
        try {
            return getResponse().getResponseHeaders().getHeaders().toMultimap(true);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public final Map<String, List<String>> getRequestProperties() {
        if (connected) {
            throw new IllegalStateException(
                    "Cannot access request header fields after connection is set");
        }
        return rawRequestHeaders.toMultimap(false);
    }

    @Override public final InputStream getInputStream() throws IOException {
        if (!doInput) {
            throw new ProtocolException("This protocol does not support input");
        }

        HttpEngine response = getResponse();

        /*
         * if the requested file does not exist, throw an exception formerly the
         * Error page from the server was returned if the requested file was
         * text/html this has changed to return FileNotFoundException for all
         * file types
         */
        if (getResponseCode() >= HTTP_BAD_REQUEST) {
            throw new FileNotFoundException(url.toString());
        }

        InputStream result = response.getResponseBody();
        if (result == null) {
            throw new ProtocolException("No response body exists; responseCode="
                    + getResponseCode());
        }
        return result;
    }

    @Override public final OutputStream getOutputStream() throws IOException {
        connect();

        OutputStream result = httpEngine.getRequestBody();
        if (result == null) {
            throw new ProtocolException("method does not support a request body: " + method);
        } else if (httpEngine.hasResponse()) {
            throw new ProtocolException("cannot write request body after response has been read");
        }

        return result;
    }

    @Override public final Permission getPermission() throws IOException {
        String connectToAddress = getConnectToHost() + ":" + getConnectToPort();
        return new SocketPermission(connectToAddress, "connect, resolve");
    }

    private String getConnectToHost() {
        return usingProxy()
                ? ((InetSocketAddress) proxy.address()).getHostName()
                : getURL().getHost();
    }

    private int getConnectToPort() {
        int hostPort = usingProxy()
                ? ((InetSocketAddress) proxy.address()).getPort()
                : getURL().getPort();
        return hostPort < 0 ? getDefaultPort() : hostPort;
    }

    @Override public final String getRequestProperty(String field) {
        if (field == null) {
            return null;
        }
        return rawRequestHeaders.get(field);
    }

    private void initHttpEngine() throws IOException {
        if (httpEngineFailure != null) {
            throw httpEngineFailure;
        } else if (httpEngine != null) {
            return;
        }

        connected = true;
        try {
            if (doOutput) {
                if (method == HttpEngine.GET) {
                    // they are requesting a stream to write to. This implies a POST method
                    method = HttpEngine.POST;
                } else if (method != HttpEngine.POST && method != HttpEngine.PUT) {
                    // If the request method is neither POST nor PUT, then you're not writing
                    throw new ProtocolException(method + " does not support writing");
                }
            }
            httpEngine = newHttpEngine(method, rawRequestHeaders, null, null);
        } catch (IOException e) {
            httpEngineFailure = e;
            throw e;
        }
    }

    /**
     * Create a new HTTP engine. This hook method is non-final so it can be
     * overridden by HttpsURLConnectionImpl.
     */
    protected HttpEngine newHttpEngine(String method, RawHeaders requestHeaders,
            Connection connection, RetryableOutputStream requestBody) throws IOException {
        return new HttpEngine(this, method, requestHeaders, connection, requestBody);
    }

    /**
     * Aggressively tries to get the final HTTP response, potentially making
     * many HTTP requests in the process in order to cope with redirects and
     * authentication.
     */
    private HttpEngine getResponse() throws IOException {
        initHttpEngine();

        if (httpEngine.hasResponse()) {
            return httpEngine;
        }

        while (true) {
            if (!execute(true)) {
                continue;
            }

            Retry retry = processResponseHeaders();
            if (retry == Retry.NONE) {
                httpEngine.automaticallyReleaseConnectionToPool();
                return httpEngine;
            }

            /*
             * The first request was insufficient. Prepare for another...
             */
            String retryMethod = method;
            OutputStream requestBody = httpEngine.getRequestBody();

            /*
             * Although RFC 2616 10.3.2 specifies that a HTTP_MOVED_PERM
             * redirect should keep the same method, Chrome, Firefox and the
             * RI all issue GETs when following any redirect.
             */
            int responseCode = getResponseCode();
            if (responseCode == HTTP_MULT_CHOICE || responseCode == HTTP_MOVED_PERM
                    || responseCode == HTTP_MOVED_TEMP || responseCode == HTTP_SEE_OTHER) {
                retryMethod = HttpEngine.GET;
                requestBody = null;
            }

            if (requestBody != null && !(requestBody instanceof RetryableOutputStream)) {
                throw new HttpRetryException("Cannot retry streamed HTTP body",
                        httpEngine.getResponseCode());
            }

            if (retry == Retry.DIFFERENT_CONNECTION) {
                httpEngine.automaticallyReleaseConnectionToPool();
            }

            httpEngine.release(true);

            httpEngine = newHttpEngine(retryMethod, rawRequestHeaders,
                    httpEngine.getConnection(), (RetryableOutputStream) requestBody);
        }
    }

    /**
     * Sends a request and optionally reads a response. Returns true if the
     * request was successfully executed, and false if the request can be
     * retried. Throws an exception if the request failed permanently.
     */
    private boolean execute(boolean readResponse) throws IOException {
        try {
            httpEngine.sendRequest();
            if (readResponse) {
                httpEngine.readResponse();
            }
            return true;
        } catch (IOException e) {
            RouteSelector routeSelector = httpEngine.routeSelector;
            if (routeSelector == null) {
                throw e; // Without a route selector, we can't retry.
            } else if (httpEngine.connection != null) {
                routeSelector.connectFailed(httpEngine.connection, e);
            }

            // The connection failure isn't fatal if there's another route to attempt.
            OutputStream requestBody = httpEngine.getRequestBody();
            if (routeSelector.hasNext() && isRecoverable(e)
                    && (requestBody == null || requestBody instanceof RetryableOutputStream)) {
                httpEngine.release(false);
                httpEngine = newHttpEngine(method, rawRequestHeaders, null,
                        (RetryableOutputStream) requestBody);
                httpEngine.routeSelector = routeSelector; // Keep the same routeSelector.
                return false;
            }
            httpEngineFailure = e;
            throw e;
        }
    }

    private boolean isRecoverable(IOException e) {
        // If the problem was a CertificateException from the X509TrustManager,
        // do not retry, we didn't have an abrupt server initiated exception.
        boolean sslFailure = e instanceof SSLHandshakeException
                && e.getCause() instanceof CertificateException;
        boolean protocolFailure = e instanceof ProtocolException;
        return !sslFailure && !protocolFailure;
    }

    HttpEngine getHttpEngine() {
        return httpEngine;
    }

    enum Retry {
        NONE,
        SAME_CONNECTION,
        DIFFERENT_CONNECTION
    }

    /**
     * Returns the retry action to take for the current response headers. The
     * headers, proxy and target URL or this connection may be adjusted to
     * prepare for a follow up request.
     */
    private Retry processResponseHeaders() throws IOException {
        switch (getResponseCode()) {
        case HTTP_PROXY_AUTH:
            if (!usingProxy()) {
                throw new ProtocolException(
                        "Received HTTP_PROXY_AUTH (407) code while not using proxy");
            }
            // fall-through
        case HTTP_UNAUTHORIZED:
            boolean credentialsFound = HttpAuthenticator.processAuthHeader(getResponseCode(),
                    httpEngine.getResponseHeaders().getHeaders(), rawRequestHeaders, proxy, url);
            return credentialsFound ? Retry.SAME_CONNECTION : Retry.NONE;

        case HTTP_MULT_CHOICE:
        case HTTP_MOVED_PERM:
        case HTTP_MOVED_TEMP:
        case HTTP_SEE_OTHER:
            if (!getInstanceFollowRedirects()) {
                return Retry.NONE;
            }
            if (++redirectionCount > MAX_REDIRECTS) {
                throw new ProtocolException("Too many redirects");
            }
            String location = getHeaderField("Location");
            if (location == null) {
                return Retry.NONE;
            }
            URL previousUrl = url;
            url = new URL(previousUrl, location);
            if (!previousUrl.getProtocol().equals(url.getProtocol())) {
                return Retry.NONE; // the scheme changed; don't retry.
            }
            if (previousUrl.getHost().equals(url.getHost())
                    && Libcore.getEffectivePort(previousUrl) == Libcore.getEffectivePort(url)) {
                return Retry.SAME_CONNECTION;
            } else {
                return Retry.DIFFERENT_CONNECTION;
            }

        default:
            return Retry.NONE;
        }
    }

    final int getDefaultPort() {
        return defaultPort;
    }

    /** @see java.net.HttpURLConnection#setFixedLengthStreamingMode(int) */
    final int getFixedContentLength() {
        return fixedContentLength;
    }

    /** @see java.net.HttpURLConnection#setChunkedStreamingMode(int) */
    final int getChunkLength() {
        return chunkLength;
    }

    final Proxy getProxy() {
        return proxy;
    }

    final void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    @Override public final boolean usingProxy() {
        return (proxy != null && proxy.type() != Proxy.Type.DIRECT);
    }

    @Override public String getResponseMessage() throws IOException {
        return getResponse().getResponseHeaders().getHeaders().getResponseMessage();
    }

    @Override public final int getResponseCode() throws IOException {
        return getResponse().getResponseCode();
    }

    @Override public final void setRequestProperty(String field, String newValue) {
        if (connected) {
            throw new IllegalStateException("Cannot set request property after connection is made");
        }
        if (field == null) {
            throw new NullPointerException("field == null");
        }
        rawRequestHeaders.set(field, newValue);
    }

    @Override public final void addRequestProperty(String field, String value) {
        if (connected) {
            throw new IllegalStateException("Cannot add request property after connection is made");
        }
        if (field == null) {
            throw new NullPointerException("field == null");
        }
        rawRequestHeaders.add(field, value);
    }
}
