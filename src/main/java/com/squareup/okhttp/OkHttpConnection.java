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

package com.squareup.okhttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketPermission;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import libcore.net.http.HttpEngine;

/**
 * An {@link java.net.URLConnection} for HTTP (<a
 * href="http://tools.ietf.org/html/rfc2616">RFC 2616</a>) used to send and
 * receive data over the web. Data may be of any type and length. This class may
 * be used to send and receive streaming data whose length is not known in
 * advance.
 *
 * <p>Uses of this class follow a pattern:
 * <ol>
 *   <li>Obtain a new {@code HttpURLConnection} by calling {@link
 *       java.net.URL#openConnection() URL.openConnection()} and casting the result to
 *       {@code HttpURLConnection}.
 *   <li>Prepare the request. The primary property of a request is its URI.
 *       Request headers may also include metadata such as credentials, preferred
 *       content types, and session cookies.
 *   <li>Optionally upload a request body. Instances must be configured with
 *       {@link #setDoOutput(boolean) setDoOutput(true)} if they include a
 *       request body. Transmit data by writing to the stream returned by {@link
 *       #getOutputStream()}.
 *   <li>Read the response. Response headers typically include metadata such as
 *       the response body's content type and length, modified dates and session
 *       cookies. The response body may be read from the stream returned by {@link
 *       #getInputStream()}. If the response has no body, that method returns an
 *       empty stream.
 *   <li>Disconnect. Once the response body has been read, the {@code
 *       HttpURLConnection} should be closed by calling {@link #disconnect()}.
 *       Disconnecting releases the resources held by a connection so they may
 *       be closed or reused.
 * </ol>
 *
 * <p>For example, to retrieve the webpage at {@code http://www.android.com/}:
 * <pre>   {@code
 *   URL url = new URL("http://www.android.com/");
 *   HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
 *   try {
 *     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
 *     readStream(in);
 *   } finally {
 *     urlConnection.disconnect();
 *   }
 * }</pre>
 *
 * <h3>Secure Communication with HTTPS</h3>
 * Calling {@link java.net.URL#openConnection()} on a URL with the "https"
 * scheme will return an {@code HttpsURLConnection}, which allows for
 * overriding the default {@link javax.net.ssl.HostnameVerifier
 * HostnameVerifier} and {@link javax.net.ssl.SSLSocketFactory
 * SSLSocketFactory}. An application-supplied {@code SSLSocketFactory}
 * created from an {@link javax.net.ssl.SSLContext SSLContext} can
 * provide a custom {@link javax.net.ssl.X509TrustManager
 * X509TrustManager} for verifying certificate chains and a custom
 * {@link javax.net.ssl.X509KeyManager X509KeyManager} for supplying
 * client certificates. See {@link OkHttpsConnection HttpsURLConnection} for
 * more details.
 *
 * <h3>Response Handling</h3>
 * {@code HttpURLConnection} will follow up to five HTTP redirects. It will
 * follow redirects from one origin server to another. This implementation
 * doesn't follow redirects from HTTPS to HTTP or vice versa.
 *
 * <p>If the HTTP response indicates that an error occurred, {@link
 * #getInputStream()} will throw an {@link java.io.IOException}. Use {@link
 * #getErrorStream()} to read the error response. The headers can be read in
 * the normal way using {@link #getHeaderFields()},
 *
 * <h3>Posting Content</h3>
 * To upload data to a web server, configure the connection for output using
 * {@link #setDoOutput(boolean) setDoOutput(true)}.
 *
 * <p>For best performance, you should call either {@link
 * #setFixedLengthStreamingMode(int)} when the body length is known in advance,
 * or {@link #setChunkedStreamingMode(int)} when it is not. Otherwise {@code
 * HttpURLConnection} will be forced to buffer the complete request body in
 * memory before it is transmitted, wasting (and possibly exhausting) heap and
 * increasing latency.
 *
 * <p>For example, to perform an upload: <pre>   {@code
 *   HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
 *   try {
 *     urlConnection.setDoOutput(true);
 *     urlConnection.setChunkedStreamingMode(0);
 *
 *     OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
 *     writeStream(out);
 *
 *     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
 *     readStream(in);
 *   } finally {
 *     urlConnection.disconnect();
 *   }
 * }</pre>
 *
 * <h3>Performance</h3>
 * The input and output streams returned by this class are <strong>not
 * buffered</strong>. Most callers should wrap the returned streams with {@link
 * java.io.BufferedInputStream BufferedInputStream} or {@link
 * java.io.BufferedOutputStream BufferedOutputStream}. Callers that do only bulk
 * reads or writes may omit buffering.
 *
 * <p>When transferring large amounts of data to or from a server, use streams
 * to limit how much data is in memory at once. Unless you need the entire
 * body to be in memory at once, process it as a stream (rather than storing
 * the complete body as a single byte array or string).
 *
 * <p>To reduce latency, this class may reuse the same underlying {@code Socket}
 * for multiple request/response pairs. As a result, HTTP connections may be
 * held open longer than necessary. Calls to {@link #disconnect()} may return
 * the socket to a pool of connected sockets. This behavior can be disabled by
 * setting the {@code http.keepAlive} system property to {@code false} before
 * issuing any HTTP requests. The {@code http.maxConnections} property may be
 * used to control how many idle connections to each server will be held.
 *
 * <p>By default, this implementation of {@code HttpURLConnection} requests that
 * servers use gzip compression. Since {@link #getContentLength()} returns the
 * number of bytes transmitted, you cannot use that method to predict how many
 * bytes can be read from {@link #getInputStream()}. Instead, read that stream
 * until it is exhausted: when {@link java.io.InputStream#read} returns -1. Gzip
 * compression can be disabled by setting the acceptable encodings in the
 * request header: <pre>   {@code
 *   urlConnection.setRequestProperty("Accept-Encoding", "identity");
 * }</pre>
 *
 * <h3>Handling Network Sign-On</h3>
 * Some Wi-Fi networks block Internet access until the user clicks through a
 * sign-on page. Such sign-on pages are typically presented by using HTTP
 * redirects. You can use {@link #getURL()} to test if your connection has been
 * unexpectedly redirected. This check is not valid until <strong>after</strong>
 * the response headers have been received, which you can trigger by calling
 * {@link #getHeaderFields()} or {@link #getInputStream()}. For example, to
 * check that a response was not redirected to an unexpected host:
 * <pre>   {@code
 *   HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
 *   try {
 *     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
 *     if (!url.getHost().equals(urlConnection.getURL().getHost())) {
 *       // we were redirected! Kick the user out to the browser to sign on?
 *     }
 *     ...
 *   } finally {
 *     urlConnection.disconnect();
 *   }
 * }</pre>
 *
 * <h3>HTTP Authentication</h3>
 * {@code HttpURLConnection} supports <a
 * href="http://www.ietf.org/rfc/rfc2617">HTTP basic authentication</a>. Use
 * {@link java.net.Authenticator} to set the VM-wide authentication handler:
 * <pre>   {@code
 *   Authenticator.setDefault(new Authenticator() {
 *     protected PasswordAuthentication getPasswordAuthentication() {
 *       return new PasswordAuthentication(username, password.toCharArray());
 *     }
 *   });
 * }</pre>
 * Unless paired with HTTPS, this is <strong>not</strong> a secure mechanism for
 * user authentication. In particular, the username, password, request and
 * response are all transmitted over the network without encryption.
 *
 * <h3>Sessions with Cookies</h3>
 * To establish and maintain a potentially long-lived session between client
 * and server, {@code HttpURLConnection} includes an extensible cookie manager.
 * Enable VM-wide cookie management using {@link java.net.CookieHandler} and {@link
 * java.net.CookieManager}: <pre>   {@code
 *   CookieManager cookieManager = new CookieManager();
 *   CookieHandler.setDefault(cookieManager);
 * }</pre>
 * By default, {@code CookieManager} accepts cookies from the <a
 * href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec1.html">origin
 * server</a> only. Two other policies are included: {@link
 * java.net.CookiePolicy#ACCEPT_ALL} and {@link java.net.CookiePolicy#ACCEPT_NONE}. Implement
 * {@link java.net.CookiePolicy} to define a custom policy.
 *
 * <p>The default {@code CookieManager} keeps all accepted cookies in memory. It
 * will forget these cookies when the VM exits. Implement {@link java.net.CookieStore} to
 * define a custom cookie store.
 *
 * <p>In addition to the cookies set by HTTP responses, you may set cookies
 * programmatically. To be included in HTTP request headers, cookies must have
 * the domain and path properties set.
 *
 * <p>By default, new instances of {@code HttpCookie} work only with servers
 * that support <a href="http://www.ietf.org/rfc/rfc2965.txt">RFC 2965</a>
 * cookies. Many web servers support only the older specification, <a
 * href="http://www.ietf.org/rfc/rfc2109.txt">RFC 2109</a>. For compatibility
 * with the most web servers, set the cookie version to 0.
 *
 * <p>For example, to receive {@code www.twitter.com} in French: <pre>   {@code
 *   HttpCookie cookie = new HttpCookie("lang", "fr");
 *   cookie.setDomain("twitter.com");
 *   cookie.setPath("/");
 *   cookie.setVersion(0);
 *   cookieManager.getCookieStore().add(new URI("http://twitter.com/"), cookie);
 * }</pre>
 *
 * <h3>HTTP Methods</h3>
 * <p>{@code HttpURLConnection} uses the {@code GET} method by default. It will
 * use {@code POST} if {@link #setDoOutput setDoOutput(true)} has been called.
 * Other HTTP methods ({@code OPTIONS}, {@code HEAD}, {@code PUT}, {@code
 * DELETE} and {@code TRACE}) can be used with {@link #setRequestMethod}.
 *
 * <h3>Proxies</h3>
 * By default, this class will connect directly to the <a
 * href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec1.html">origin
 * server</a>. It can also connect via an {@link java.net.Proxy.Type#HTTP HTTP} or {@link
 * java.net.Proxy.Type#SOCKS SOCKS} proxy. To use a proxy, use {@link
 * java.net.URL#openConnection(java.net.Proxy) URL.openConnection(Proxy)} when creating the
 * connection.
 *
 * <h3>IPv6 Support</h3>
 * <p>This class includes transparent support for IPv6. For hosts with both IPv4
 * and IPv6 addresses, it will attempt to connect to each of a host's addresses
 * until a connection is established.
 *
 * <h3>Response Caching</h3>
 * Android 4.0 (Ice Cream Sandwich) includes a response cache. See {@code
 * android.net.http.HttpResponseCache} for instructions on enabling HTTP caching
 * in your application.
 *
 * <h3>Avoiding Bugs In Earlier Releases</h3>
 * Prior to Android 2.2 (Froyo), this class had some frustrating bugs. In
 * particular, calling {@code close()} on a readable {@code InputStream} could
 * <a href="http://code.google.com/p/android/issues/detail?id=2939">poison the
 * connection pool</a>. Work around this by disabling connection pooling:
 * <pre>   {@code
 * private void disableConnectionReuseIfNecessary() {
 *   // Work around pre-Froyo bugs in HTTP connection reuse.
 *   if (Integer.parseInt(Build.VERSION.SDK) < Build.VERSION_CODES.FROYO) {
 *     System.setProperty("http.keepAlive", "false");
 *   }
 * }}</pre>
 *
 * <p>Each instance of {@code HttpURLConnection} may be used for one
 * request/response pair. Instances of this class are not thread safe.
 */
public abstract class OkHttpConnection extends URLConnection {

    /**
     * The subset of HTTP methods that the user may select via {@link
     * #setRequestMethod(String)}.
     */
    private static final String[] PERMITTED_USER_METHODS = {
            HttpEngine.OPTIONS,
            HttpEngine.GET,
            HttpEngine.HEAD,
            HttpEngine.POST,
            HttpEngine.PUT,
            HttpEngine.DELETE,
            HttpEngine.TRACE
            // Note: we don't allow users to specify "CONNECT"
    };

    /**
     * The HTTP request method of this {@code HttpURLConnection}. The default
     * value is {@code "GET"}.
     */
    protected String method = HttpEngine.GET;

    /**
     * The status code of the response obtained from the HTTP request. The
     * default value is {@code -1}.
     * <p>
     * <li>1xx: Informational</li>
     * <li>2xx: Success</li>
     * <li>3xx: Relocation/Redirection</li>
     * <li>4xx: Client Error</li>
     * <li>5xx: Server Error</li>
     */
    protected int responseCode = -1;

    /**
     * The HTTP response message which corresponds to the response code.
     */
    protected String responseMessage;

    /**
     * Flag to define whether the protocol will automatically follow redirects
     * or not. The default value is {@code true}.
     */
    protected boolean instanceFollowRedirects = followRedirects;

    private static boolean followRedirects = true;

    /**
     * If the HTTP chunked encoding is enabled this parameter defines the
     * chunk-length. Default value is {@code -1} that means the chunked encoding
     * mode is disabled.
     */
    protected int chunkLength = -1;

    /**
     * If using HTTP fixed-length streaming mode this parameter defines the
     * fixed length of content. Default value is {@code -1} that means the
     * fixed-length streaming mode is disabled.
     */
    protected int fixedContentLength = -1;

    // 2XX: generally "OK"
    // 3XX: relocation/redirect
    // 4XX: client error
    // 5XX: server error
    /**
     * Numeric status code, 202: Accepted
     */
    public static final int HTTP_ACCEPTED = 202;

    /**
     * Numeric status code, 502: Bad Gateway
     */
    public static final int HTTP_BAD_GATEWAY = 502;

    /**
     * Numeric status code, 405: Bad Method
     */
    public static final int HTTP_BAD_METHOD = 405;

    /**
     * Numeric status code, 400: Bad Request
     */
    public static final int HTTP_BAD_REQUEST = 400;

    /**
     * Numeric status code, 408: Client Timeout
     */
    public static final int HTTP_CLIENT_TIMEOUT = 408;

    /**
     * Numeric status code, 409: Conflict
     */
    public static final int HTTP_CONFLICT = 409;

    /**
     * Numeric status code, 201: Created
     */
    public static final int HTTP_CREATED = 201;

    /**
     * Numeric status code, 413: Entity too large
     */
    public static final int HTTP_ENTITY_TOO_LARGE = 413;

    /**
     * Numeric status code, 403: Forbidden
     */
    public static final int HTTP_FORBIDDEN = 403;

    /**
     * Numeric status code, 504: Gateway timeout
     */
    public static final int HTTP_GATEWAY_TIMEOUT = 504;

    /**
     * Numeric status code, 410: Gone
     */
    public static final int HTTP_GONE = 410;

    /**
     * Numeric status code, 500: Internal error
     */
    public static final int HTTP_INTERNAL_ERROR = 500;

    /**
     * Numeric status code, 411: Length required
     */
    public static final int HTTP_LENGTH_REQUIRED = 411;

    /**
     * Numeric status code, 301 Moved permanently
     */
    public static final int HTTP_MOVED_PERM = 301;

    /**
     * Numeric status code, 302: Moved temporarily
     */
    public static final int HTTP_MOVED_TEMP = 302;

    /**
     * Numeric status code, 300: Multiple choices
     */
    public static final int HTTP_MULT_CHOICE = 300;

    /**
     * Numeric status code, 204: No content
     */
    public static final int HTTP_NO_CONTENT = 204;

    /**
     * Numeric status code, 406: Not acceptable
     */
    public static final int HTTP_NOT_ACCEPTABLE = 406;

    /**
     * Numeric status code, 203: Not authoritative
     */
    public static final int HTTP_NOT_AUTHORITATIVE = 203;

    /**
     * Numeric status code, 404: Not found
     */
    public static final int HTTP_NOT_FOUND = 404;

    /**
     * Numeric status code, 501: Not implemented
     */
    public static final int HTTP_NOT_IMPLEMENTED = 501;

    /**
     * Numeric status code, 304: Not modified
     */
    public static final int HTTP_NOT_MODIFIED = 304;

    /**
     * Numeric status code, 200: OK
     */
    public static final int HTTP_OK = 200;

    /**
     * Numeric status code, 206: Partial
     */
    public static final int HTTP_PARTIAL = 206;

    /**
     * Numeric status code, 402: Payment required
     */
    public static final int HTTP_PAYMENT_REQUIRED = 402;

    /**
     * Numeric status code, 412: Precondition failed
     */
    public static final int HTTP_PRECON_FAILED = 412;

    /**
     * Numeric status code, 407: Proxy authentication required
     */
    public static final int HTTP_PROXY_AUTH = 407;

    /**
     * Numeric status code, 414: Request too long
     */
    public static final int HTTP_REQ_TOO_LONG = 414;

    /**
     * Numeric status code, 205: Reset
     */
    public static final int HTTP_RESET = 205;

    /**
     * Numeric status code, 303: See other
     */
    public static final int HTTP_SEE_OTHER = 303;

    /**
     * Numeric status code, 500: Internal error
     *
     * @deprecated Use {@link #HTTP_INTERNAL_ERROR}
     */
    @Deprecated
    public static final int HTTP_SERVER_ERROR = 500;

    /**
     * Numeric status code, 305: Use proxy.
     *
     * <p>Like Firefox and Chrome, this class doesn't honor this response code.
     * Other implementations respond to this status code by retrying the request
     * using the HTTP proxy named by the response's Location header field.
     */
    public static final int HTTP_USE_PROXY = 305;

    /**
     * Numeric status code, 401: Unauthorized
     */
    public static final int HTTP_UNAUTHORIZED = 401;

    /**
     * Numeric status code, 415: Unsupported type
     */
    public static final int HTTP_UNSUPPORTED_TYPE = 415;

    /**
     * Numeric status code, 503: Unavailable
     */
    public static final int HTTP_UNAVAILABLE = 503;

    /**
     * Numeric status code, 505: Version not supported
     */
    public static final int HTTP_VERSION = 505;

    public static OkHttpConnection open(URL url) {
        return new libcore.net.http.HttpURLConnectionImpl(url, 443);
    }

    public static OkHttpConnection open(URL url, Proxy proxy) {
        return new libcore.net.http.HttpURLConnectionImpl(url, 443, proxy);
    }

    /**
     * Constructs a new {@code HttpURLConnection} instance pointing to the
     * resource specified by the {@code url}.
     *
     * @param url
     *            the URL of this connection.
     * @see java.net.URL
     * @see java.net.URLConnection
     */
    protected OkHttpConnection(URL url) {
        super(url);
    }

    /**
     * Releases this connection so that its resources may be either reused or
     * closed.
     *
     * <p>Unlike other Java implementations, this will not necessarily close
     * socket connections that can be reused. You can disable all connection
     * reuse by setting the {@code http.keepAlive} system property to {@code
     * false} before issuing any HTTP requests.
     */
    public abstract void disconnect();

    /**
     * Returns an input stream from the server in the case of an error such as
     * the requested file has not been found on the remote server. This stream
     * can be used to read the data the server will send back.
     *
     * @return the error input stream returned by the server.
     */
    public InputStream getErrorStream() {
        return null;
    }

    /**
     * Returns the value of {@code followRedirects} which indicates if this
     * connection follows a different URL redirected by the server. It is
     * enabled by default.
     *
     * @return the value of the flag.
     * @see #setFollowRedirects
     */
    public static boolean getFollowRedirects() {
        return followRedirects;
    }

    /**
     * Returns the permission object (in this case {@code SocketPermission})
     * with the host and the port number as the target name and {@code
     * "resolve, connect"} as the action list. If the port number of this URL
     * instance is lower than {@code 0} the port will be set to {@code 80}.
     *
     * @return the permission object required for this connection.
     * @throws java.io.IOException
     *             if an IO exception occurs during the creation of the
     *             permission object.
     */
    @Override
    public java.security.Permission getPermission() throws IOException {
        int port = url.getPort();
        if (port < 0) {
            port = 80;
        }
        return new SocketPermission(url.getHost() + ":" + port,
                "connect, resolve");
    }

    /**
     * Returns the request method which will be used to make the request to the
     * remote HTTP server. All possible methods of this HTTP implementation is
     * listed in the class definition.
     *
     * @return the request method string.
     * @see #method
     * @see #setRequestMethod
     */
    public String getRequestMethod() {
        return method;
    }

    /**
     * Returns the response code returned by the remote HTTP server.
     *
     * @return the response code, -1 if no valid response code.
     * @throws java.io.IOException
     *             if there is an IO error during the retrieval.
     * @see #getResponseMessage
     */
    public int getResponseCode() throws IOException {
        // Call getInputStream() first since getHeaderField() doesn't return
        // exceptions
        getInputStream();
        String response = getHeaderField(0);
        if (response == null) {
            return -1;
        }
        response = response.trim();
        int mark = response.indexOf(" ") + 1;
        if (mark == 0) {
            return -1;
        }
        int last = mark + 3;
        if (last > response.length()) {
            last = response.length();
        }
        responseCode = Integer.parseInt(response.substring(mark, last));
        if (last + 1 <= response.length()) {
            responseMessage = response.substring(last + 1);
        }
        return responseCode;
    }

    /**
     * Returns the response message returned by the remote HTTP server.
     *
     * @return the response message. {@code null} if no such response exists.
     * @throws java.io.IOException
     *             if there is an error during the retrieval.
     * @see #getResponseCode()
     */
    public String getResponseMessage() throws IOException {
        if (responseMessage != null) {
            return responseMessage;
        }
        getResponseCode();
        return responseMessage;
    }

    /**
     * Sets the flag of whether this connection will follow redirects returned
     * by the remote server.
     *
     * @param auto
     *            the value to enable or disable this option.
     */
    public static void setFollowRedirects(boolean auto) {
        followRedirects = auto;
    }

    /**
     * Sets the request command which will be sent to the remote HTTP server.
     * This method can only be called before the connection is made.
     *
     * @param method
     *            the string representing the method to be used.
     * @throws java.net.ProtocolException
     *             if this is called after connected, or the method is not
     *             supported by this HTTP implementation.
     * @see #getRequestMethod()
     * @see #method
     */
    public void setRequestMethod(String method) throws ProtocolException {
        if (connected) {
            throw new ProtocolException("Connection already established");
        }
        for (String permittedUserMethod : PERMITTED_USER_METHODS) {
            if (permittedUserMethod.equals(method)) {
                // if there is a supported method that matches the desired
                // method, then set the current method and return
                this.method = permittedUserMethod;
                return;
            }
        }
        // if none matches, then throw ProtocolException
        throw new ProtocolException("Unknown method '" + method + "'; must be one of " +
                Arrays.toString(PERMITTED_USER_METHODS));
    }

    /**
     * Returns whether this connection uses a proxy server or not.
     *
     * @return {@code true} if this connection passes a proxy server, false
     *         otherwise.
     */
    public abstract boolean usingProxy();

    /**
     * Returns the encoding used to transmit the response body over the network.
     * This is null or "identity" if the content was not encoded, or "gzip" if
     * the body was gzip compressed. Most callers will be more interested in the
     * {@link #getContentType() content type}, which may also include the
     * content's character encoding.
     */
    @Override public String getContentEncoding() {
        return super.getContentEncoding(); // overridden for Javadoc only
    }

    /**
     * Returns whether this connection follows redirects.
     *
     * @return {@code true} if this connection follows redirects, false
     *         otherwise.
     */
    public boolean getInstanceFollowRedirects() {
        return instanceFollowRedirects;
    }

    /**
     * Sets whether this connection follows redirects.
     *
     * @param followRedirects
     *            {@code true} if this connection will follows redirects, false
     *            otherwise.
     */
    public void setInstanceFollowRedirects(boolean followRedirects) {
        instanceFollowRedirects = followRedirects;
    }

    /**
     * Returns the date value in milliseconds since {@code 01.01.1970, 00:00h}
     * corresponding to the header field {@code field}. The {@code defaultValue}
     * will be returned if no such field can be found in the response header.
     *
     * @param field
     *            the header field name.
     * @param defaultValue
     *            the default value to use if the specified header field wont be
     *            found.
     * @return the header field represented in milliseconds since January 1,
     *         1970 GMT.
     */
    @Override
    public long getHeaderFieldDate(String field, long defaultValue) {
        return super.getHeaderFieldDate(field, defaultValue);
    }

    /**
     * If the length of a HTTP request body is known ahead, sets fixed length to
     * enable streaming without buffering. Sets after connection will cause an
     * exception.
     *
     * @see #setChunkedStreamingMode
     * @param contentLength
     *            the fixed length of the HTTP request body.
     * @throws IllegalStateException
     *             if already connected or another mode already set.
     * @throws IllegalArgumentException
     *             if {@code contentLength} is less than zero.
     */
    public void setFixedLengthStreamingMode(int contentLength) {
        if (super.connected) {
            throw new IllegalStateException("Already connected");
        }
        if (chunkLength > 0) {
            throw new IllegalStateException("Already in chunked mode");
        }
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength < 0");
        }
        this.fixedContentLength = contentLength;
    }

    /**
     * Stream a request body whose length is not known in advance. Old HTTP/1.0
     * only servers may not support this mode.
     *
     * <p>When HTTP chunked encoding is used, the stream is divided into
     * chunks, each prefixed with a header containing the chunk's size. Setting
     * a large chunk length requires a large internal buffer, potentially
     * wasting memory. Setting a small chunk length increases the number of
     * bytes that must be transmitted because of the header on every chunk.
     * Most caller should use {@code 0} to get the system default.
     *
     * @see #setFixedLengthStreamingMode
     * @param chunkLength the length to use, or {@code 0} for the default chunk
     *     length.
     * @throws IllegalStateException if already connected or another mode
     *     already set.
     */
    public void setChunkedStreamingMode(int chunkLength) {
        if (super.connected) {
            throw new IllegalStateException("Already connected");
        }
        if (fixedContentLength >= 0) {
            throw new IllegalStateException("Already in fixed-length mode");
        }
        if (chunkLength <= 0) {
            this.chunkLength = HttpEngine.DEFAULT_CHUNK_LENGTH;
        } else {
            this.chunkLength = chunkLength;
        }
    }
}
