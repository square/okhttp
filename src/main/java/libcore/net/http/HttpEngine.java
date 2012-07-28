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

package libcore.net.http;

import com.squareup.okhttp.OkHttpConnection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.CookieHandler;
import java.net.Proxy;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLSocketFactory;
import libcore.io.IoUtils;
import libcore.util.EmptyArray;
import libcore.util.ExtendedResponseCache;
import libcore.util.Libcore;
import libcore.util.ResponseSource;

/**
 * Handles a single HTTP request/response pair. Each HTTP engine follows this
 * lifecycle:
 * <ol>
 *     <li>It is created.
 *     <li>The HTTP request message is sent with sendRequest(). Once the request
 *         is sent it is an error to modify the request headers. After
 *         sendRequest() has been called the request body can be written to if
 *         it exists.
 *     <li>The HTTP response message is read with readResponse(). After the
 *         response has been read the response headers and body can be read.
 *         All responses have a response body input stream, though in some
 *         instances this stream is empty.
 * </ol>
 *
 * <p>The request and response may be served by the HTTP response cache, by the
 * network, or by both in the event of a conditional GET.
 *
 * <p>This class may hold a socket connection that needs to be released or
 * recycled. By default, this socket connection is held when the last byte of
 * the response is consumed. To release the connection when it is no longer
 * required, use {@link #automaticallyReleaseConnectionToPool()}.
 */
public class HttpEngine {
    private static final CacheResponse BAD_GATEWAY_RESPONSE = new CacheResponse() {
        @Override public Map<String, List<String>> getHeaders() throws IOException {
            Map<String, List<String>> result = new HashMap<String, List<String>>();
            result.put(null, Collections.singletonList("HTTP/1.1 502 Bad Gateway"));
            return result;
        }
        @Override public InputStream getBody() throws IOException {
            return new ByteArrayInputStream(EmptyArray.BYTE);
        }
    };
    public static final int DEFAULT_CHUNK_LENGTH = 1024;

    public static final String OPTIONS = "OPTIONS";
    public static final String GET = "GET";
    public static final String HEAD = "HEAD";
    public static final String POST = "POST";
    public static final String PUT = "PUT";
    public static final String DELETE = "DELETE";
    public static final String TRACE = "TRACE";
    public static final String CONNECT = "CONNECT";

    public static final int HTTP_CONTINUE = 100;

    protected final HttpURLConnectionImpl policy;

    protected final String method;

    private ResponseSource responseSource;

    protected HttpConnection connection;
    private OutputStream requestBodyOut;

    private Transport transport;

    private InputStream responseBodyIn;

    private final ResponseCache responseCache = ResponseCache.getDefault();
    private CacheResponse cacheResponse;
    private CacheRequest cacheRequest;

    /** The time when the request headers were written, or -1 if they haven't been written yet. */
    long sentRequestMillis = -1;

    /**
     * True if this client added an "Accept-Encoding: gzip" header field and is
     * therefore responsible for also decompressing the transfer stream.
     */
    private boolean transparentGzip;

    final URI uri;

    final RequestHeaders requestHeaders;

    /** Null until a response is received from the network or the cache. */
    ResponseHeaders responseHeaders;

    /*
     * The cache response currently being validated on a conditional get. Null
     * if the cached response doesn't exist or doesn't need validation. If the
     * conditional get succeeds, these will be used for the response headers and
     * body. If it fails, these be closed and set to null.
     */
    private ResponseHeaders cachedResponseHeaders;
    private InputStream cachedResponseBody;

    /**
     * True if the socket connection should be released to the connection pool
     * when the response has been fully read.
     */
    private boolean automaticallyReleaseConnectionToPool;

    /** True if the socket connection is no longer needed by this engine. */
    private boolean connectionReleased;

    /**
     * @param requestHeaders the client's supplied request headers. This class
     *     creates a private copy that it can mutate.
     * @param connection the connection used for an intermediate response
     *     immediately prior to this request/response pair, such as a same-host
     *     redirect. This engine assumes ownership of the connection and must
     *     release it when it is unneeded.
     */
    public HttpEngine(HttpURLConnectionImpl policy, String method, RawHeaders requestHeaders,
            HttpConnection connection, RetryableOutputStream requestBodyOut) throws IOException {
        this.policy = policy;
        this.method = method;
        this.connection = connection;
        this.requestBodyOut = requestBodyOut;

        try {
            uri = Libcore.toUriLenient(policy.getURL());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        this.requestHeaders = new RequestHeaders(uri, new RawHeaders(requestHeaders));
    }

    public URI getUri() {
        return uri;
    }

    /**
     * Figures out what the response source will be, and opens a socket to that
     * source if necessary. Prepares the request headers and gets ready to start
     * writing the request body if it exists.
     */
    public final void sendRequest() throws IOException {
        if (responseSource != null) {
            return;
        }

        prepareRawRequestHeaders();
        initResponseSource();
        if (responseCache instanceof ExtendedResponseCache) {
            ((ExtendedResponseCache) responseCache).trackResponse(responseSource);
        }

        /*
         * The raw response source may require the network, but the request
         * headers may forbid network use. In that case, dispose of the network
         * response and use a BAD_GATEWAY response instead.
         */
        if (requestHeaders.isOnlyIfCached() && responseSource.requiresConnection()) {
            if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
                IoUtils.closeQuietly(cachedResponseBody);
            }
            this.responseSource = ResponseSource.CACHE;
            this.cacheResponse = BAD_GATEWAY_RESPONSE;
            RawHeaders rawResponseHeaders = RawHeaders.fromMultimap(cacheResponse.getHeaders());
            setResponse(new ResponseHeaders(uri, rawResponseHeaders), cacheResponse.getBody());
        }

        if (responseSource.requiresConnection()) {
            sendSocketRequest();
        } else if (connection != null) {
            HttpConnectionPool.INSTANCE.recycle(connection);
            connection = null;
        }
    }

    /**
     * Initialize the source for this response. It may be corrected later if the
     * request headers forbids network use.
     */
    private void initResponseSource() throws IOException {
        responseSource = ResponseSource.NETWORK;
        if (!policy.getUseCaches() || responseCache == null) {
            return;
        }

        CacheResponse candidate = responseCache.get(uri, method,
                requestHeaders.getHeaders().toMultimap());
        if (candidate == null) {
            return;
        }

        Map<String, List<String>> responseHeadersMap = candidate.getHeaders();
        cachedResponseBody = candidate.getBody();
        if (!acceptCacheResponseType(candidate)
                || responseHeadersMap == null
                || cachedResponseBody == null) {
            IoUtils.closeQuietly(cachedResponseBody);
            return;
        }

        RawHeaders rawResponseHeaders = RawHeaders.fromMultimap(responseHeadersMap);
        cachedResponseHeaders = new ResponseHeaders(uri, rawResponseHeaders);
        long now = System.currentTimeMillis();
        this.responseSource = cachedResponseHeaders.chooseResponseSource(now, requestHeaders);
        if (responseSource == ResponseSource.CACHE) {
            this.cacheResponse = candidate;
            setResponse(cachedResponseHeaders, cachedResponseBody);
        } else if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
            this.cacheResponse = candidate;
        } else if (responseSource == ResponseSource.NETWORK) {
            IoUtils.closeQuietly(cachedResponseBody);
        } else {
            throw new AssertionError();
        }
    }

    private void sendSocketRequest() throws IOException {
        if (connection == null) {
            connect();
        }

        if (transport != null) {
            throw new IllegalStateException();
        }

        transport = connection.newTransport(this);

        if (hasRequestBody() && requestBodyOut == null) {
            // Create a request body if we don't have one already. We'll already
            // have one if we're retrying a failed POST.
            requestBodyOut = transport.createRequestBody();
        }
    }

    /**
     * Connect to the origin server either directly or via a proxy.
     */
    protected void connect() throws IOException {
        if (connection == null) {
            connection = openSocketConnection();
        }
    }

    protected final HttpConnection openSocketConnection() throws IOException {
        HttpConnection result = HttpConnection.connect(uri, getSslSocketFactory(),
                policy.getProxy(), requiresTunnel(), policy.getConnectTimeout());
        Proxy proxy = result.getAddress().getProxy();
        if (proxy != null) {
            policy.setProxy(proxy);
            // Add the authority to the request line when we're using a proxy.
            requestHeaders.getHeaders().setStatusLine(getRequestLine());
        }
        result.setSoTimeout(policy.getReadTimeout());
        return result;
    }

    /**
     * @param body the response body, or null if it doesn't exist or isn't
     *     available.
     */
    private void setResponse(ResponseHeaders headers, InputStream body) throws IOException {
        if (this.responseBodyIn != null) {
            throw new IllegalStateException();
        }
        this.responseHeaders = headers;
        if (body != null) {
            initContentStream(body);
        }
    }

    boolean hasRequestBody() {
        return method == POST || method == PUT;
    }

    /**
     * Returns the request body or null if this request doesn't have a body.
     */
    public final OutputStream getRequestBody() {
        if (responseSource == null) {
            throw new IllegalStateException();
        }
        return requestBodyOut;
    }

    public final boolean hasResponse() {
        return responseHeaders != null;
    }

    public final RequestHeaders getRequestHeaders() {
        return requestHeaders;
    }

    public final ResponseHeaders getResponseHeaders() {
        if (responseHeaders == null) {
            throw new IllegalStateException();
        }
        return responseHeaders;
    }

    public final int getResponseCode() {
        if (responseHeaders == null) {
            throw new IllegalStateException();
        }
        return responseHeaders.getHeaders().getResponseCode();
    }

    public final InputStream getResponseBody() {
        if (responseHeaders == null) {
            throw new IllegalStateException();
        }
        return responseBodyIn;
    }

    public final CacheResponse getCacheResponse() {
        return cacheResponse;
    }

    public final HttpConnection getConnection() {
        return connection;
    }

    public final boolean hasRecycledConnection() {
        return connection != null && connection.isRecycled();
    }

    /**
     * Returns true if {@code cacheResponse} is of the right type. This
     * condition is necessary but not sufficient for the cached response to
     * be used.
     */
    protected boolean acceptCacheResponseType(CacheResponse cacheResponse) {
        return true;
    }

    private void maybeCache() throws IOException {
        // Are we caching at all?
        if (!policy.getUseCaches() || responseCache == null) {
            return;
        }

        // Should we cache this response for this request?
        if (!responseHeaders.isCacheable(requestHeaders)) {
            return;
        }

        // Offer this request to the cache.
        cacheRequest = responseCache.put(uri, getHttpConnectionToCache());
    }

    protected OkHttpConnection getHttpConnectionToCache() {
        return policy;
    }

    /**
     * Cause the socket connection to be released to the connection pool when
     * it is no longer needed. If it is already unneeded, it will be pooled
     * immediately. Otherwise the connection is held so that redirects can be
     * handled by the same connection.
     */
    public final void automaticallyReleaseConnectionToPool() {
        automaticallyReleaseConnectionToPool = true;
        if (connection != null && connectionReleased) {
            HttpConnectionPool.INSTANCE.recycle(connection);
            connection = null;
        }
    }

    /**
     * Releases this engine so that its resources may be either reused or
     * closed. Also call {@link #automaticallyReleaseConnectionToPool} unless
     * the connection will be used to follow a redirect.
     */
    public final void release(boolean reusable) {
        // If the response body comes from the cache, close it.
        if (responseBodyIn == cachedResponseBody) {
            IoUtils.closeQuietly(responseBodyIn);
        }

        if (!connectionReleased && connection != null) {
            connectionReleased = true;

            if (!reusable || !transport.makeReusable(requestBodyOut, responseBodyIn)) {
                connection.closeSocketAndStreams();
                connection = null;
            } else if (automaticallyReleaseConnectionToPool) {
                HttpConnectionPool.INSTANCE.recycle(connection);
                connection = null;
            }
        }
    }

    private void initContentStream(InputStream transferStream) throws IOException {
        if (transparentGzip && responseHeaders.isContentEncodingGzip()) {
            /*
             * If the response was transparently gzipped, remove the gzip header field
             * so clients don't double decompress. http://b/3009828
             */
            responseHeaders.stripContentEncoding();
            responseBodyIn = new GZIPInputStream(transferStream);
        } else {
            responseBodyIn = transferStream;
        }
    }

    /**
     * Returns true if the response must have a (possibly 0-length) body.
     * See RFC 2616 section 4.3.
     */
    public final boolean hasResponseBody() {
        int responseCode = responseHeaders.getHeaders().getResponseCode();

        // HEAD requests never yield a body regardless of the response headers.
        if (method == HEAD) {
            return false;
        }

        if (method != CONNECT
                && (responseCode < HTTP_CONTINUE || responseCode >= 200)
                && responseCode != HttpURLConnectionImpl.HTTP_NO_CONTENT
                && responseCode != HttpURLConnectionImpl.HTTP_NOT_MODIFIED) {
            return true;
        }

        /*
         * If the Content-Length or Transfer-Encoding headers disagree with the
         * response code, the response is malformed. For best compatibility, we
         * honor the headers.
         */
        if (responseHeaders.getContentLength() != -1 || responseHeaders.isChunked()) {
            return true;
        }

        return false;
    }

    /**
     * Populates requestHeaders with defaults and cookies.
     *
     * <p>This client doesn't specify a default {@code Accept} header because it
     * doesn't know what content types the application is interested in.
     */
    private void prepareRawRequestHeaders() throws IOException {
        requestHeaders.getHeaders().setStatusLine(getRequestLine());

        if (requestHeaders.getUserAgent() == null) {
            requestHeaders.setUserAgent(getDefaultUserAgent());
        }

        if (requestHeaders.getHost() == null) {
            requestHeaders.setHost(getOriginAddress(policy.getURL()));
        }

        // TODO: this shouldn't be set for SPDY (it's ignored)
        if ((connection == null || connection.httpMinorVersion != 0)
                && requestHeaders.getConnection() == null) {
            requestHeaders.setConnection("Keep-Alive");
        }

        if (requestHeaders.getAcceptEncoding() == null) {
            transparentGzip = true;
            // TODO: this shouldn't be set for SPDY (it isn't necessary)
            requestHeaders.setAcceptEncoding("gzip");
        }

        if (hasRequestBody() && requestHeaders.getContentType() == null) {
            requestHeaders.setContentType("application/x-www-form-urlencoded");
        }

        long ifModifiedSince = policy.getIfModifiedSince();
        if (ifModifiedSince != 0) {
            requestHeaders.setIfModifiedSince(new Date(ifModifiedSince));
        }

        CookieHandler cookieHandler = CookieHandler.getDefault();
        if (cookieHandler != null) {
            requestHeaders.addCookies(
                    cookieHandler.get(uri, requestHeaders.getHeaders().toMultimap()));
        }
    }

    /**
     * Returns the request status line, like "GET / HTTP/1.1". This is exposed
     * to the application by {@link HttpURLConnectionImpl#getHeaderFields}, so
     * it needs to be set even if the transport is SPDY.
     */
    String getRequestLine() {
        String protocol = (connection == null || connection.httpMinorVersion != 0)
                ? "HTTP/1.1"
                : "HTTP/1.0";
        return method + " " + requestString() + " " + protocol;
    }

    private String requestString() {
        URL url = policy.getURL();
        if (includeAuthorityInRequestLine()) {
            return url.toString();
        } else {
            String fileOnly = url.getFile();
            if (fileOnly == null) {
                fileOnly = "/";
            } else if (!fileOnly.startsWith("/")) {
                fileOnly = "/" + fileOnly;
            }
            return fileOnly;
        }
    }

    /**
     * Returns true if the request line should contain the full URL with host
     * and port (like "GET http://android.com/foo HTTP/1.1") or only the path
     * (like "GET /foo HTTP/1.1").
     *
     * <p>This is non-final because for HTTPS it's never necessary to supply the
     * full URL, even if a proxy is in use.
     */
    protected boolean includeAuthorityInRequestLine() {
        return policy.usingProxy();
    }

    /**
     * Returns the SSL configuration for connections created by this engine.
     * We cannot reuse HTTPS connections if the socket factory has changed.
     */
    protected SSLSocketFactory getSslSocketFactory() {
        return null;
    }

    protected final String getDefaultUserAgent() {
        String agent = System.getProperty("http.agent");
        return agent != null ? agent : ("Java" + System.getProperty("java.version"));
    }

    protected final String getOriginAddress(URL url) {
        int port = url.getPort();
        String result = url.getHost();
        if (port > 0 && port != policy.getDefaultPort()) {
            result = result + ":" + port;
        }
        return result;
    }

    protected boolean requiresTunnel() {
        return false;
    }

    /**
     * Flushes the remaining request header and body, parses the HTTP response
     * headers and starts reading the HTTP response body if it exists.
     */
    public final void readResponse() throws IOException {
        if (hasResponse()) {
            return;
        }

        if (responseSource == null) {
            throw new IllegalStateException("readResponse() without sendRequest()");
        }

        if (!responseSource.requiresConnection()) {
            return;
        }

        if (sentRequestMillis == -1) {
            if (requestBodyOut instanceof RetryableOutputStream) {
                int contentLength = ((RetryableOutputStream) requestBodyOut).contentLength();
                requestHeaders.setContentLength(contentLength);
            }
            transport.writeRequestHeaders();
        }

        if (requestBodyOut != null) {
            requestBodyOut.close();
            if (requestBodyOut instanceof RetryableOutputStream) {
                transport.writeRequestBody((RetryableOutputStream) requestBodyOut);
            }
        }

        transport.flushRequest();

        responseHeaders = transport.readResponseHeaders();
        responseHeaders.setLocalTimestamps(sentRequestMillis, System.currentTimeMillis());

        if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
            if (cachedResponseHeaders.validate(responseHeaders)) {
                release(true);
                ResponseHeaders combinedHeaders = cachedResponseHeaders.combine(responseHeaders);
                setResponse(combinedHeaders, cachedResponseBody);
                if (responseCache instanceof ExtendedResponseCache) {
                    ExtendedResponseCache httpResponseCache = (ExtendedResponseCache) responseCache;
                    httpResponseCache.trackConditionalCacheHit();
                    httpResponseCache.update(cacheResponse, getHttpConnectionToCache());
                }
                return;
            } else {
                IoUtils.closeQuietly(cachedResponseBody);
            }
        }

        if (hasResponseBody()) {
            maybeCache(); // reentrant. this calls into user code which may call back into this!
        }

        initContentStream(transport.getTransferStream(cacheRequest));
    }
}
