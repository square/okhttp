/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp.android.test;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketPermission;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.security.AccessControlException;
import java.security.Permission;
import java.security.Principal;
import java.security.cert.Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;
import okio.Timeout;
import org.jetbrains.annotations.NotNull;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;

/**
 * OkHttp 3.14 dropped support for the long-deprecated OkUrlFactory class, which allows you to use
 * the HttpURLConnection API with OkHttp's implementation. This class does the same thing using only
 * public APIs in OkHttp. It requires OkHttp 3.14 or newer.
 *
 * <p>Rather than pasting this 1100 line gist into your source code, please upgrade to OkHttp's
 * request/response API. Your code will be shorter, easier to read, and you'll be able to use
 * interceptors.
 */
public final class ObsoleteUrlFactory implements URLStreamHandlerFactory, Cloneable {
  static final String SELECTED_PROTOCOL = "ObsoleteUrlFactory-Selected-Protocol";

  static final String RESPONSE_SOURCE = "ObsoleteUrlFactory-Response-Source";

  static final Set<String> METHODS = new LinkedHashSet<>(
      Arrays.asList("OPTIONS", "GET", "HEAD", "POST", "PUT", "DELETE", "TRACE", "PATCH"));

  static final TimeZone UTC = TimeZone.getTimeZone("GMT");

  static final int HTTP_CONTINUE = 100;

  static final ThreadLocal<DateFormat> STANDARD_DATE_FORMAT = ThreadLocal.withInitial(() -> {
    // Date format specified by RFC 7231 section 7.1.1.1.
    DateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    rfc1123.setLenient(false);
    rfc1123.setTimeZone(UTC);
    return rfc1123;
  });

  static final Comparator<String> FIELD_NAME_COMPARATOR = (a, b) -> {
    // @FindBugsSuppressWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    if (a == b) {
      return 0;
    } else if (a == null) {
      return -1;
    } else if (b == null) {
      return 1;
    } else {
      return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }
  };

  private OkHttpClient client;

  public ObsoleteUrlFactory(OkHttpClient client) {
    this.client = client;
  }

  public OkHttpClient client() {
    return client;
  }

  public ObsoleteUrlFactory setClient(OkHttpClient client) {
    this.client = client;
    return this;
  }

  /**
   * Returns a copy of this stream handler factory that includes a shallow copy of the internal
   * {@linkplain OkHttpClient HTTP client}.
   */
  @Override public ObsoleteUrlFactory clone() {
    return new ObsoleteUrlFactory(client);
  }

  public HttpURLConnection open(URL url) {
    return open(url, client.proxy());
  }

  HttpURLConnection open(URL url, @Nullable Proxy proxy) {
    String protocol = url.getProtocol();
    OkHttpClient copy = client.newBuilder()
        .proxy(proxy)
        .build();

    if (protocol.equals("http")) return new OkHttpURLConnection(url, copy);
    if (protocol.equals("https")) return new OkHttpsURLConnection(url, copy);
    throw new IllegalArgumentException("Unexpected protocol: " + protocol);
  }

  /**
   * Creates a URLStreamHandler as a {@link java.net.URL#setURLStreamHandlerFactory}.
   *
   * <p>This code configures OkHttp to handle all HTTP and HTTPS connections
   * created with {@link java.net.URL#openConnection()}: <pre>   {@code
   *
   *   OkHttpClient okHttpClient = new OkHttpClient();
   *   URL.setURLStreamHandlerFactory(new ObsoleteUrlFactory(okHttpClient));
   * }</pre>
   */
  @Override public URLStreamHandler createURLStreamHandler(final String protocol) {
    if (!protocol.equals("http") && !protocol.equals("https")) return null;

    return new URLStreamHandler() {
      @Override protected URLConnection openConnection(URL url) {
        return open(url);
      }

      @Override protected URLConnection openConnection(URL url, Proxy proxy) {
        return open(url, proxy);
      }

      @Override protected int getDefaultPort() {
        if (protocol.equals("http")) return 80;
        if (protocol.equals("https")) return 443;
        throw new AssertionError();
      }
    };
  }

  static String format(Date value) {
    return STANDARD_DATE_FORMAT.get().format(value);
  }

  static boolean permitsRequestBody(String method) {
    return !(method.equals("GET") || method.equals("HEAD"));
  }

  /** Returns true if the response must have a (possibly 0-length) body. See RFC 7231. */
  static boolean hasBody(Response response) {
    // HEAD requests never yield a body regardless of the response headers.
    if (response.request().method().equals("HEAD")) {
      return false;
    }

    int responseCode = response.code();
    if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HTTP_NO_CONTENT
        && responseCode != HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
    // response is malformed. For best compatibility, we honor the headers.
    if (contentLength(response.headers()) != -1
        || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
      return true;
    }

    return false;
  }

  static long contentLength(Headers headers) {
    String s = headers.get("Content-Length");
    if (s == null) return -1;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static String responseSourceHeader(Response response) {
    if (response.networkResponse() == null) {
      return response.cacheResponse() == null
          ? "NONE"
          : "CACHE " + response.code();
    }
    return response.cacheResponse() == null
        ? "NETWORK " + response.code()
        : "CONDITIONAL_CACHE " + response.networkResponse().code();
  }

  static String statusLineToString(Response response) {
    return (response.protocol() == Protocol.HTTP_1_0 ? "HTTP/1.0" : "HTTP/1.1")
        + ' ' + response.code()
        + ' ' + response.message();
  }

  static String toHumanReadableAscii(String s) {
    for (int i = 0, length = s.length(), c; i < length; i += Character.charCount(c)) {
      c = s.codePointAt(i);
      if (c > '\u001f' && c < '\u007f') continue;

      Buffer buffer = new Buffer();
      buffer.writeUtf8(s, 0, i);
      buffer.writeUtf8CodePoint('?');
      for (int j = i + Character.charCount(c); j < length; j += Character.charCount(c)) {
        c = s.codePointAt(j);
        buffer.writeUtf8CodePoint(c > '\u001f' && c < '\u007f' ? c : '?');
      }
      return buffer.readUtf8();
    }
    return s;
  }

  static Map<String, List<String>> toMultimap(Headers headers, @Nullable String valueForNullKey) {
    Map<String, List<String>> result = new TreeMap<>(FIELD_NAME_COMPARATOR);
    for (int i = 0, size = headers.size(); i < size; i++) {
      String fieldName = headers.name(i);
      String value = headers.value(i);

      List<String> allValues = new ArrayList<>();
      List<String> otherValues = result.get(fieldName);
      if (otherValues != null) {
        allValues.addAll(otherValues);
      }
      allValues.add(value);
      result.put(fieldName, Collections.unmodifiableList(allValues));
    }
    if (valueForNullKey != null) {
      result.put(null, Collections.unmodifiableList(Collections.singletonList(valueForNullKey)));
    }
    return Collections.unmodifiableMap(result);
  }

  static String getSystemProperty(String key, @Nullable String defaultValue) {
    String value;
    try {
      value = System.getProperty(key);
    } catch (AccessControlException ex) {
      return defaultValue;
    }
    return value != null ? value : defaultValue;
  }

  static String defaultUserAgent() {
    String agent = getSystemProperty("http.agent", null);
    return agent != null ? toHumanReadableAscii(agent) : "ObsoleteUrlFactory";
  }

  static IOException propagate(Throwable throwable) throws IOException {
    if (throwable instanceof IOException) throw (IOException) throwable;
    if (throwable instanceof Error) throw (Error) throwable;
    if (throwable instanceof RuntimeException) throw (RuntimeException) throwable;
    throw new AssertionError();
  }

  static final class OkHttpURLConnection extends HttpURLConnection implements Callback {
    // These fields are confined to the application thread that uses HttpURLConnection.
    OkHttpClient client;
    final NetworkInterceptor networkInterceptor = new NetworkInterceptor();
    Headers.Builder requestHeaders = new Headers.Builder();
    Headers responseHeaders;
    boolean executed;
    Call call;

    /** Like the superclass field of the same name, but a long and available on all platforms. */
    long fixedContentLength = -1L;

    // These fields are guarded by lock.
    private final Object lock = new Object();
    private Response response;
    private Throwable callFailure;
    Response networkResponse;
    boolean connectPending = true;
    Proxy proxy;
    Handshake handshake;

    OkHttpURLConnection(URL url, OkHttpClient client) {
      super(url);
      this.client = client;
    }

    @Override public void connect() throws IOException {
      if (executed) return;

      Call call = buildCall();
      executed = true;
      call.enqueue(this);

      synchronized (lock) {
        try {
          while (connectPending && response == null && callFailure == null) {
            lock.wait(); // Wait 'til the network interceptor is reached or the call fails.
          }
          if (callFailure != null) {
            throw propagate(callFailure);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // Retain interrupted status.
          throw new InterruptedIOException();
        }
      }
    }

    @Override public void disconnect() {
      // Calling disconnect() before a connection exists should have no effect.
      if (call == null) return;

      networkInterceptor.proceed(); // Unblock any waiting async thread.
      call.cancel();
    }

    @Override public InputStream getErrorStream() {
      try {
        Response response = getResponse(true);
        if (hasBody(response) && response.code() >= HTTP_BAD_REQUEST) {
          return response.body().byteStream();
        }
        return null;
      } catch (IOException e) {
        return null;
      }
    }

    Headers getHeaders() throws IOException {
      if (responseHeaders == null) {
        Response response = getResponse(true);
        Headers headers = response.headers();
        responseHeaders = headers.newBuilder()
            .add(SELECTED_PROTOCOL, response.protocol().toString())
            .add(RESPONSE_SOURCE, responseSourceHeader(response))
            .build();
      }
      return responseHeaders;
    }

    @Override public String getHeaderField(int position) {
      try {
        Headers headers = getHeaders();
        if (position < 0 || position >= headers.size()) return null;
        return headers.value(position);
      } catch (IOException e) {
        return null;
      }
    }

    @Override public String getHeaderField(String fieldName) {
      try {
        return fieldName == null
            ? statusLineToString(getResponse(true))
            : getHeaders().get(fieldName);
      } catch (IOException e) {
        return null;
      }
    }

    @Override public String getHeaderFieldKey(int position) {
      try {
        Headers headers = getHeaders();
        if (position < 0 || position >= headers.size()) return null;
        return headers.name(position);
      } catch (IOException e) {
        return null;
      }
    }

    @Override public Map<String, List<String>> getHeaderFields() {
      try {
        return toMultimap(getHeaders(), statusLineToString(getResponse(true)));
      } catch (IOException e) {
        return Collections.emptyMap();
      }
    }

    @Override public Map<String, List<String>> getRequestProperties() {
      if (connected) {
        throw new IllegalStateException(
            "Cannot access request header fields after connection is set");
      }

      return toMultimap(requestHeaders.build(), null);
    }

    @Override public InputStream getInputStream() throws IOException {
      if (!doInput) {
        throw new ProtocolException("This protocol does not support input");
      }

      Response response = getResponse(false);
      if (response.code() >= HTTP_BAD_REQUEST) throw new FileNotFoundException(url.toString());
      return response.body().byteStream();
    }

    @Override public OutputStream getOutputStream() throws IOException {
      OutputStreamRequestBody requestBody = (OutputStreamRequestBody) buildCall().request().body();
      if (requestBody == null) {
        throw new ProtocolException("method does not support a request body: " + method);
      }

      if (requestBody instanceof StreamedRequestBody) {
        connect();
        networkInterceptor.proceed();
      }

      if (requestBody.closed) {
        throw new ProtocolException("cannot write request body after response has been read");
      }

      return requestBody.outputStream;
    }

    @Override public Permission getPermission() {
      URL url = getURL();
      String hostname = url.getHost();
      int hostPort = url.getPort() != -1
          ? url.getPort()
          : HttpUrl.defaultPort(url.getProtocol());
      if (usingProxy()) {
        InetSocketAddress proxyAddress = (InetSocketAddress) client.proxy().address();
        hostname = proxyAddress.getHostName();
        hostPort = proxyAddress.getPort();
      }
      return new SocketPermission(hostname + ":" + hostPort, "connect, resolve");
    }

    @Override public String getRequestProperty(String field) {
      if (field == null) return null;
      return requestHeaders.get(field);
    }

    @Override public void setConnectTimeout(int timeoutMillis) {
      client = client.newBuilder()
          .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
          .build();
    }

    @Override public void setInstanceFollowRedirects(boolean followRedirects) {
      client = client.newBuilder()
          .followRedirects(followRedirects)
          .build();
    }

    @Override public boolean getInstanceFollowRedirects() {
      return client.followRedirects();
    }

    @Override public int getConnectTimeout() {
      return client.connectTimeoutMillis();
    }

    @Override public void setReadTimeout(int timeoutMillis) {
      client = client.newBuilder()
          .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
          .build();
    }

    @Override public int getReadTimeout() {
      return client.readTimeoutMillis();
    }

    private Call buildCall() throws IOException {
      if (call != null) {
        return call;
      }

      connected = true;
      if (doOutput) {
        if (method.equals("GET")) {
          method = "POST";
        } else if (!permitsRequestBody(method)) {
          throw new ProtocolException(method + " does not support writing");
        }
      }

      if (requestHeaders.get("User-Agent") == null) {
        requestHeaders.add("User-Agent", defaultUserAgent());
      }

      OutputStreamRequestBody requestBody = null;
      if (permitsRequestBody(method)) {
        String contentType = requestHeaders.get("Content-Type");
        if (contentType == null) {
          contentType = "application/x-www-form-urlencoded";
          requestHeaders.add("Content-Type", contentType);
        }

        boolean stream = fixedContentLength != -1L || chunkLength > 0;

        long contentLength = -1L;
        String contentLengthString = requestHeaders.get("Content-Length");
        if (fixedContentLength != -1L) {
          contentLength = fixedContentLength;
        } else if (contentLengthString != null) {
          contentLength = Long.parseLong(contentLengthString);
        }

        requestBody = stream
            ? new StreamedRequestBody(contentLength)
            : new BufferedRequestBody(contentLength);
        requestBody.timeout.timeout(client.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
      }

      HttpUrl url;
      try {
        url = HttpUrl.get(getURL().toString());
      } catch (IllegalArgumentException e) {
        MalformedURLException malformedUrl = new MalformedURLException();
        malformedUrl.initCause(e);
        throw malformedUrl;
      }

      Request request = new Request.Builder()
          .url(url)
          .headers(requestHeaders.build())
          .method(method, requestBody)
          .build();

      OkHttpClient.Builder clientBuilder = client.newBuilder();
      clientBuilder.interceptors().clear();
      clientBuilder.interceptors().add(UnexpectedException.INTERCEPTOR);
      clientBuilder.networkInterceptors().clear();
      clientBuilder.networkInterceptors().add(networkInterceptor);

      // Use a separate dispatcher so that limits aren't impacted. But use the same executor service!
      clientBuilder.dispatcher(new Dispatcher(client.dispatcher().executorService()));

      // If we're currently not using caches, make sure the engine's client doesn't have one.
      if (!getUseCaches()) {
        clientBuilder.cache(null);
      }

      return call = clientBuilder.build().newCall(request);
    }

    private Response getResponse(boolean networkResponseOnError) throws IOException {
      synchronized (lock) {
        if (response != null) return response;
        if (callFailure != null) {
          if (networkResponseOnError && networkResponse != null) return networkResponse;
          throw propagate(callFailure);
        }
      }

      Call call = buildCall();
      networkInterceptor.proceed();

      OutputStreamRequestBody requestBody = (OutputStreamRequestBody) call.request().body();
      if (requestBody != null) requestBody.outputStream.close();

      if (executed) {
        synchronized (lock) {
          try {
            while (response == null && callFailure == null) {
              lock.wait(); // Wait until the response is returned or the call fails.
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Retain interrupted status.
            throw new InterruptedIOException();
          }
        }
      } else {
        executed = true;
        try {
          onResponse(call, call.execute());
        } catch (IOException e) {
          onFailure(call, e);
        }
      }

      synchronized (lock) {
        if (callFailure != null) throw propagate(callFailure);
        if (response != null) return response;
      }

      throw new AssertionError();
    }

    @Override public boolean usingProxy() {
      if (proxy != null) return true;
      Proxy clientProxy = client.proxy();
      return clientProxy != null && clientProxy.type() != Proxy.Type.DIRECT;
    }

    @Override public String getResponseMessage() throws IOException {
      return getResponse(true).message();
    }

    @Override public int getResponseCode() throws IOException {
      return getResponse(true).code();
    }

    @Override public void setRequestProperty(String field, String newValue) {
      if (connected) {
        throw new IllegalStateException("Cannot set request property after connection is made");
      }
      if (field == null) {
        throw new NullPointerException("field == null");
      }
      if (newValue == null) {
        return;
      }

      requestHeaders.set(field, newValue);
    }

    @Override public void setIfModifiedSince(long newValue) {
      super.setIfModifiedSince(newValue);
      if (ifModifiedSince != 0) {
        requestHeaders.set("If-Modified-Since", format(new Date(ifModifiedSince)));
      } else {
        requestHeaders.removeAll("If-Modified-Since");
      }
    }

    @Override public void addRequestProperty(String field, String value) {
      if (connected) {
        throw new IllegalStateException("Cannot add request property after connection is made");
      }
      if (field == null) {
        throw new NullPointerException("field == null");
      }
      if (value == null) {
        return;
      }

      requestHeaders.add(field, value);
    }

    @Override public void setRequestMethod(String method) throws ProtocolException {
      if (!METHODS.contains(method)) {
        throw new ProtocolException("Expected one of " + METHODS + " but was " + method);
      }
      this.method = method;
    }

    @Override public void setFixedLengthStreamingMode(int contentLength) {
      setFixedLengthStreamingMode((long) contentLength);
    }

    @Override public void setFixedLengthStreamingMode(long contentLength) {
      if (super.connected) throw new IllegalStateException("Already connected");
      if (chunkLength > 0) throw new IllegalStateException("Already in chunked mode");
      if (contentLength < 0) throw new IllegalArgumentException("contentLength < 0");
      this.fixedContentLength = contentLength;
      super.fixedContentLength = (int) Math.min(contentLength, Integer.MAX_VALUE);
    }

    @Override public void onFailure(@NotNull Call call, @NotNull IOException e) {
      synchronized (lock) {
        this.callFailure = (e instanceof UnexpectedException) ? e.getCause() : e;
        lock.notifyAll();
      }
    }

    @Override public void onResponse(@NotNull Call call, @NotNull Response response) {
      synchronized (lock) {
        this.response = response;
        this.handshake = response.handshake();
        this.url = response.request().url().url();
        lock.notifyAll();
      }
    }

    final class NetworkInterceptor implements Interceptor {
      // Guarded by HttpUrlConnection.this.
      private boolean proceed;

      public void proceed() {
        synchronized (lock) {
          this.proceed = true;
          lock.notifyAll();
        }
      }

      @Override public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        synchronized (lock) {
          connectPending = false;
          proxy = chain.connection().route().proxy();
          handshake = chain.connection().handshake();
          lock.notifyAll();

          try {
            while (!proceed) {
              lock.wait(); // Wait until proceed() is called.
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Retain interrupted status.
            throw new InterruptedIOException();
          }
        }

        // Try to lock in the Content-Length before transmitting the request body.
        if (request.body() instanceof OutputStreamRequestBody) {
          OutputStreamRequestBody requestBody = (OutputStreamRequestBody) request.body();
          request = requestBody.prepareToSendRequest(request);
        }

        Response response = chain.proceed(request);

        synchronized (lock) {
          networkResponse = response;
          url = response.request().url().url();
        }

        return response;
      }
    }
  }

  abstract static class OutputStreamRequestBody extends RequestBody {
    Timeout timeout;
    long expectedContentLength;
    OutputStream outputStream;
    boolean closed;

    void initOutputStream(BufferedSink sink, long expectedContentLength) {
      this.timeout = sink.timeout();
      this.expectedContentLength = expectedContentLength;

      // An output stream that writes to sink. If expectedContentLength is not -1, then this expects
      // exactly that many bytes to be written.
      this.outputStream = new OutputStream() {
        private long bytesReceived;

        @Override public void write(int b) throws IOException {
          write(new byte[] {(byte) b}, 0, 1);
        }

        @Override public void write(byte[] source, int offset, int byteCount) throws IOException {
          if (closed) throw new IOException("closed"); // Not IllegalStateException!

          if (expectedContentLength != -1L && bytesReceived + byteCount > expectedContentLength) {
            throw new ProtocolException("expected " + expectedContentLength
                + " bytes but received " + bytesReceived + byteCount);
          }

          bytesReceived += byteCount;
          try {
            sink.write(source, offset, byteCount);
          } catch (InterruptedIOException e) {
            throw new SocketTimeoutException(e.getMessage());
          }
        }

        @Override public void flush() throws IOException {
          if (closed) return; // Weird, but consistent with historical behavior.
          sink.flush();
        }

        @Override public void close() throws IOException {
          closed = true;

          if (expectedContentLength != -1L && bytesReceived < expectedContentLength) {
            throw new ProtocolException("expected " + expectedContentLength
                + " bytes but received " + bytesReceived);
          }

          sink.close();
        }
      };
    }

    @Override public long contentLength() {
      return expectedContentLength;
    }

    @Override public final @Nullable MediaType contentType() {
      return null; // Let the caller provide this in a regular header.
    }

    public Request prepareToSendRequest(Request request) throws IOException {
      return request;
    }
  }

  static final class BufferedRequestBody extends OutputStreamRequestBody {
    final Buffer buffer = new Buffer();
    long contentLength = -1L;

    BufferedRequestBody(long expectedContentLength) {
      initOutputStream(buffer, expectedContentLength);
    }

    @Override public long contentLength() {
      return contentLength;
    }

    @Override public Request prepareToSendRequest(Request request) throws IOException {
      if (request.header("Content-Length") != null) return request;

      outputStream.close();
      contentLength = buffer.size();
      return request.newBuilder()
          .removeHeader("Transfer-Encoding")
          .header("Content-Length", Long.toString(buffer.size()))
          .build();
    }

    @Override public void writeTo(BufferedSink sink) {
      buffer.copyTo(sink.buffer(), 0, buffer.size());
    }
  }

  static final class StreamedRequestBody extends OutputStreamRequestBody {
    private final Pipe pipe = new Pipe(8192);

    StreamedRequestBody(long expectedContentLength) {
      initOutputStream(Okio.buffer(pipe.sink()), expectedContentLength);
    }

    @Override public boolean isOneShot() {
      return true;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      Buffer buffer = new Buffer();
      while (pipe.source().read(buffer, 8192) != -1L) {
        sink.write(buffer, buffer.size());
      }
    }
  }

  abstract static class DelegatingHttpsURLConnection extends HttpsURLConnection {
    private final HttpURLConnection delegate;

    DelegatingHttpsURLConnection(HttpURLConnection delegate) {
      super(delegate.getURL());
      this.delegate = delegate;
    }

    protected abstract Handshake handshake();

    @Override public abstract void setHostnameVerifier(HostnameVerifier hostnameVerifier);

    @Override public abstract HostnameVerifier getHostnameVerifier();

    @Override public abstract void setSSLSocketFactory(SSLSocketFactory sslSocketFactory);

    @Override public abstract SSLSocketFactory getSSLSocketFactory();

    @Override public String getCipherSuite() {
      Handshake handshake = handshake();
      return handshake != null ? handshake.cipherSuite().javaName() : null;
    }

    @Override public Certificate[] getLocalCertificates() {
      Handshake handshake = handshake();
      if (handshake == null) return null;
      List<Certificate> result = handshake.localCertificates();
      return !result.isEmpty() ? result.toArray(new Certificate[result.size()]) : null;
    }

    @Override public Certificate[] getServerCertificates() {
      Handshake handshake = handshake();
      if (handshake == null) return null;
      List<Certificate> result = handshake.peerCertificates();
      return !result.isEmpty() ? result.toArray(new Certificate[result.size()]) : null;
    }

    @Override public Principal getPeerPrincipal() {
      Handshake handshake = handshake();
      return handshake != null ? handshake.peerPrincipal() : null;
    }

    @Override public Principal getLocalPrincipal() {
      Handshake handshake = handshake();
      return handshake != null ? handshake.localPrincipal() : null;
    }

    @Override public void connect() throws IOException {
      connected = true;
      delegate.connect();
    }

    @Override public void disconnect() {
      delegate.disconnect();
    }

    @Override public InputStream getErrorStream() {
      return delegate.getErrorStream();
    }

    @Override public String getRequestMethod() {
      return delegate.getRequestMethod();
    }

    @Override public int getResponseCode() throws IOException {
      return delegate.getResponseCode();
    }

    @Override public String getResponseMessage() throws IOException {
      return delegate.getResponseMessage();
    }

    @Override public void setRequestMethod(String method) throws ProtocolException {
      delegate.setRequestMethod(method);
    }

    @Override public boolean usingProxy() {
      return delegate.usingProxy();
    }

    @Override public boolean getInstanceFollowRedirects() {
      return delegate.getInstanceFollowRedirects();
    }

    @Override public void setInstanceFollowRedirects(boolean followRedirects) {
      delegate.setInstanceFollowRedirects(followRedirects);
    }

    @Override public boolean getAllowUserInteraction() {
      return delegate.getAllowUserInteraction();
    }

    @Override public Object getContent() throws IOException {
      return delegate.getContent();
    }

    @Override public Object getContent(Class[] types) throws IOException {
      return delegate.getContent(types);
    }

    @Override public String getContentEncoding() {
      return delegate.getContentEncoding();
    }

    @Override public int getContentLength() {
      return delegate.getContentLength();
    }

    // Should only be invoked on Java 8+ or Android API 24+.
    @Override public long getContentLengthLong() {
      return delegate.getContentLengthLong();
    }

    @Override public String getContentType() {
      return delegate.getContentType();
    }

    @Override public long getDate() {
      return delegate.getDate();
    }

    @Override public boolean getDefaultUseCaches() {
      return delegate.getDefaultUseCaches();
    }

    @Override public boolean getDoInput() {
      return delegate.getDoInput();
    }

    @Override public boolean getDoOutput() {
      return delegate.getDoOutput();
    }

    @Override public long getExpiration() {
      return delegate.getExpiration();
    }

    @Override public String getHeaderField(int pos) {
      return delegate.getHeaderField(pos);
    }

    @Override public Map<String, List<String>> getHeaderFields() {
      return delegate.getHeaderFields();
    }

    @Override public Map<String, List<String>> getRequestProperties() {
      return delegate.getRequestProperties();
    }

    @Override public void addRequestProperty(String field, String newValue) {
      delegate.addRequestProperty(field, newValue);
    }

    @Override public String getHeaderField(String key) {
      return delegate.getHeaderField(key);
    }

    // Should only be invoked on Java 8+ or Android API 24+.
    @Override public long getHeaderFieldLong(String field, long defaultValue) {
      return delegate.getHeaderFieldLong(field, defaultValue);
    }

    @Override public long getHeaderFieldDate(String field, long defaultValue) {
      return delegate.getHeaderFieldDate(field, defaultValue);
    }

    @Override public int getHeaderFieldInt(String field, int defaultValue) {
      return delegate.getHeaderFieldInt(field, defaultValue);
    }

    @Override public String getHeaderFieldKey(int position) {
      return delegate.getHeaderFieldKey(position);
    }

    @Override public long getIfModifiedSince() {
      return delegate.getIfModifiedSince();
    }

    @Override public InputStream getInputStream() throws IOException {
      return delegate.getInputStream();
    }

    @Override public long getLastModified() {
      return delegate.getLastModified();
    }

    @Override public OutputStream getOutputStream() throws IOException {
      return delegate.getOutputStream();
    }

    @Override public Permission getPermission() throws IOException {
      return delegate.getPermission();
    }

    @Override public String getRequestProperty(String field) {
      return delegate.getRequestProperty(field);
    }

    @Override public URL getURL() {
      return delegate.getURL();
    }

    @Override public boolean getUseCaches() {
      return delegate.getUseCaches();
    }

    @Override public void setAllowUserInteraction(boolean newValue) {
      delegate.setAllowUserInteraction(newValue);
    }

    @Override public void setDefaultUseCaches(boolean newValue) {
      delegate.setDefaultUseCaches(newValue);
    }

    @Override public void setDoInput(boolean newValue) {
      delegate.setDoInput(newValue);
    }

    @Override public void setDoOutput(boolean newValue) {
      delegate.setDoOutput(newValue);
    }

    // Should only be invoked on Java 8+ or Android API 24+.
    @Override public void setFixedLengthStreamingMode(long contentLength) {
      delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override public void setIfModifiedSince(long newValue) {
      delegate.setIfModifiedSince(newValue);
    }

    @Override public void setRequestProperty(String field, String newValue) {
      delegate.setRequestProperty(field, newValue);
    }

    @Override public void setUseCaches(boolean newValue) {
      delegate.setUseCaches(newValue);
    }

    @Override public void setConnectTimeout(int timeoutMillis) {
      delegate.setConnectTimeout(timeoutMillis);
    }

    @Override public int getConnectTimeout() {
      return delegate.getConnectTimeout();
    }

    @Override public void setReadTimeout(int timeoutMillis) {
      delegate.setReadTimeout(timeoutMillis);
    }

    @Override public int getReadTimeout() {
      return delegate.getReadTimeout();
    }

    @Override public String toString() {
      return delegate.toString();
    }

    @Override public void setFixedLengthStreamingMode(int contentLength) {
      delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override public void setChunkedStreamingMode(int chunkLength) {
      delegate.setChunkedStreamingMode(chunkLength);
    }
  }

  static final class OkHttpsURLConnection extends DelegatingHttpsURLConnection {
    private final OkHttpURLConnection delegate;

    OkHttpsURLConnection(URL url, OkHttpClient client) {
      this(new OkHttpURLConnection(url, client));
    }

    OkHttpsURLConnection(OkHttpURLConnection delegate) {
      super(delegate);
      this.delegate = delegate;
    }

    @Override protected Handshake handshake() {
      if (delegate.call == null) {
        throw new IllegalStateException("Connection has not yet been established");
      }

      return delegate.handshake;
    }

    @Override public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
      delegate.client = delegate.client.newBuilder()
          .hostnameVerifier(hostnameVerifier)
          .build();
    }

    @Override public HostnameVerifier getHostnameVerifier() {
      return delegate.client.hostnameVerifier();
    }

    @Override public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
      if (sslSocketFactory == null) {
        throw new IllegalArgumentException("sslSocketFactory == null");
      }
      // This fails in JDK 9 because OkHttp is unable to extract the trust manager.
      delegate.client = delegate.client.newBuilder()
          .sslSocketFactory(sslSocketFactory)
          .build();
    }

    @Override public SSLSocketFactory getSSLSocketFactory() {
      return delegate.client.sslSocketFactory();
    }
  }

  static final class UnexpectedException extends IOException {
    static final Interceptor INTERCEPTOR = chain -> {
      try {
        return chain.proceed(chain.request());
      } catch (Error | RuntimeException e) {
        throw new UnexpectedException(e);
      }
    };

    UnexpectedException(Throwable cause) {
      super(cause);
    }
  }

  public static void main(String[] args) throws Exception {
    OkHttpClient okHttpClient = new OkHttpClient();
    URL.setURLStreamHandlerFactory(new ObsoleteUrlFactory(okHttpClient));

    URL url = new URL("https://publicobject.com/helloworld.txt");
    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(urlConnection.getInputStream()))) {
      for (String line; (line = reader.readLine()) != null; ) {
        System.out.println(line);
      }
    }
  }
}
