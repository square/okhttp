/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketException;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.ws.RealWebSocket;
import okio.Buffer;
import okio.ForwardingSink;
import okio.Sink;

/**
 * Bridge between OkHttp's application and network layers. This class exposes high-level application
 * layer primitives: connections, requests, responses, and streams.
 */
public final class Transmitter {
  public final OkHttpClient client;
  public final Call call;
  public final EventListener eventListener;

  private @Nullable Object callStackTrace;

  private volatile boolean canceled;
  private volatile StreamAllocation streamAllocation;

  public Transmitter(OkHttpClient client, Call call) {
    this.client = client;
    this.call = call;
    this.eventListener = client.eventListenerFactory().create(call);
  }

  public void setCallStackTrace(@Nullable Object callStackTrace) {
    this.callStackTrace = callStackTrace;
  }

  public void newStreamAllocation(Request request) {
    newStreamAllocation(createAddress(request.url()));
  }

  public void newStreamAllocation(Address address) {
    this.streamAllocation = new StreamAllocation(this, client.connectionPool(),
        address, call, eventListener, callStackTrace);
  }

  private Address createAddress(HttpUrl url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (url.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
      certificatePinner = client.certificatePinner();
    }

    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
   * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
   * Otherwise if a socket connection is being established, that is terminated.
   */
  public void cancel() {
    canceled = true;
    StreamAllocation streamAllocation = this.streamAllocation;
    if (streamAllocation != null) streamAllocation.cancel();
  }

  public boolean isCanceled() {
    return canceled;
  }

  public void streamFailed(@Nullable IOException e) {
    streamAllocation.streamFailed(e);
  }

  public void noNewStreams() {
    streamAllocation.noNewStreams();
  }

  public RealWebSocket.Streams newWebSocketStreams() {
    return streamAllocation.connection().newWebSocketStreams(this);
  }

  public void socketTimeout(int timeout) throws SocketException {
    streamAllocation.connection().socket().setSoTimeout(timeout);
  }

  /** Returns the connection that carries the allocated stream. */
  public RealConnection newStream(Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
    streamAllocation.newStream(client, chain, doExtensiveHealthChecks);
    return streamAllocation.connection();
  }

  public Handshake handshake() {
    return streamAllocation.connection().handshake();
  }

  public boolean isConnectionMultiplexed() {
    return streamAllocation.connection().isMultiplexed();
  }

  public void releaseStreamAllocation(boolean callEnd) {
    streamAllocation.release(callEnd);
  }

  public boolean hasMoreRoutes() {
    return streamAllocation.hasMoreRoutes();
  }

  public Route route() {
    return streamAllocation.route();
  }

  public boolean hasCodec() {
    return streamAllocation != null && streamAllocation.codec() != null;
  }

  public void callStart() {
    eventListener.callStart(call);
  }

  public void callFailed(IOException e) {
    eventListener.callFailed(call, e);
  }

  public void writeRequestHeaders(Request request) throws IOException {
    eventListener.requestHeadersStart(call);
    streamAllocation.codec().writeRequestHeaders(request);
    eventListener.requestHeadersEnd(call, request);
  }

  public Sink createRequestBody(Request request) throws IOException {
    eventListener.requestBodyStart(call);
    Sink rawRequestBody = streamAllocation.codec()
        .createRequestBody(request, request.body().contentLength());
    return new RequestBodySink(rawRequestBody, request.body().contentLength());
  }

  public void flushRequest() throws IOException {
    streamAllocation.codec().flushRequest();
  }

  public void finishRequest() throws IOException {
    streamAllocation.codec().finishRequest();
  }

  public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    eventListener.responseHeadersStart(call);
    return streamAllocation.codec().readResponseHeaders(expectContinue);
  }

  public void responseHeadersEnd(Response response) {
    eventListener.responseHeadersEnd(call, response);
  }

  public ResponseBody openResponseBody(Response response) throws IOException {
    streamAllocation.eventListener.responseBodyStart(streamAllocation.call);
    return streamAllocation.codec().openResponseBody(response);
  }

  public DeferredTrailers deferredTrailers() {
    return new DeferredTrailers() {
      HttpCodec codec = streamAllocation.codec();

      @Override public Headers trailers() throws IOException {
        return codec.trailers();
      }
    };
  }

  public boolean supportsUrl(HttpUrl url) {
    return streamAllocation.connection().supportsUrl(url);
  }

  public void acquire(RealConnection connection, boolean reportedAcquired) {
    streamAllocation.acquire(connection, reportedAcquired);
  }

  public RealConnection connection() {
    return streamAllocation.connection();
  }

  public Socket releaseAndAcquire(RealConnection newConnection) {
    return streamAllocation.releaseAndAcquire(newConnection);
  }

  public void streamFinished(boolean noNewStreams, long bytesRead, IOException e) {
    if (streamAllocation != null) {
      streamAllocation.streamFinished(noNewStreams, streamAllocation.codec(), bytesRead, e);
    }
  }

  @Override public String toString() {
    return call.request().url().redact();
  }

  /** A request body that fires events when it completes. */
  private final class RequestBodySink extends ForwardingSink {
    private boolean closed;
    private long bytesReceived;

    /** The exact number of bytes to be written, or -1L if that is unknown. */
    private long bytesExpected;

    RequestBodySink(Sink delegate, long bytesExpected) {
      super(delegate);
      this.bytesExpected = bytesExpected;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (bytesExpected != -1L && bytesReceived + byteCount > bytesExpected) {
        throw new ProtocolException("expected " + bytesExpected
            + " bytes but received " + (bytesReceived + byteCount));
      }
      super.write(source, byteCount);
      this.bytesReceived += byteCount;
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (bytesExpected != -1L && bytesReceived != bytesExpected) {
        throw new ProtocolException("unexpected end of stream");
      }
      eventListener.requestBodyEnd(call, bytesReceived);
      super.close();
    }
  }

  public static final class TransmitterReference extends WeakReference<Transmitter> {
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    public final Object callStackTrace;

    public TransmitterReference(Transmitter referent, Object callStackTrace) {
      super(referent);
      this.callStackTrace = callStackTrace;
    }
  }
}
