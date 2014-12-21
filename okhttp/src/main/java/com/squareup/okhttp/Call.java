/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.OkHeaders;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.logging.Level;
import okio.BufferedSink;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.Internal.logger;
import static com.squareup.okhttp.internal.http.HttpEngine.MAX_REDIRECTS;

/**
 * A call is a request that has been prepared for execution. A call can be
 * canceled. As this object represents a single request/response pair (stream),
 * it cannot be executed twice.
 */
public class Call {
  private final OkHttpClient client;

  // Guarded by this.
  private boolean executed;
  volatile boolean canceled;

  /** The application's original request unadulterated by redirects or auth headers. */
  Request originalRequest;
  HttpEngine engine;

  protected Call(OkHttpClient client, Request originalRequest) {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    this.client = client.copyWithDefaults();
    this.originalRequest = originalRequest;
  }

  /**
   * Invokes the request immediately, and blocks until the response can be
   * processed or is in error.
   *
   * <p>The caller may read the response body with the response's
   * {@link Response#body} method.  To facilitate connection recycling, callers
   * should always {@link ResponseBody#close() close the response body}.
   *
   * <p>Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer success:
   * {@code response} may still indicate an unhappy HTTP response code like 404
   * or 500.
   *
   * @throws IOException if the request could not be executed due to
   *     cancellation, a connectivity problem or timeout. Because networks can
   *     fail during an exchange, it is possible that the remote server
   *     accepted the request before the failure.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    try {
      client.getDispatcher().executed(this);
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.getDispatcher().finished(this);
    }
  }

  Object tag() {
    return originalRequest.tag();
  }

  /**
   * Schedules the request to be executed at some point in the future.
   *
   * <p>The {@link OkHttpClient#getDispatcher dispatcher} defines when the
   * request will run: usually immediately unless there are several other
   * requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either
   * an HTTP response or a failure exception. If you {@link #cancel} a request
   * before it completes the callback will not be invoked.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  public void enqueue(Callback responseCallback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    client.getDispatcher().enqueue(new AsyncCall(responseCallback));
  }

  /**
   * Cancels the request, if possible. Requests that are already complete
   * cannot be canceled.
   */
  public void cancel() {
    canceled = true;
    if (engine != null) engine.disconnect();
  }

  public boolean isCanceled() {
    return canceled;
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    private AsyncCall(Callback responseCallback) {
      super("OkHttp %s", originalRequest.urlString());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().getHost();
    }

    Request request() {
      return originalRequest;
    }

    Object tag() {
      return originalRequest.tag();
    }

    void cancel() {
      Call.this.cancel();
    }

    Call get() {
      return Call.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain();
        if (canceled) {
          signalledCallback = true;
          responseCallback.onFailure(originalRequest, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          logger.log(Level.INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          responseCallback.onFailure(engine.getRequest(), e);
        }
      } finally {
        client.getDispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = canceled ? "canceled call" : "call";
    try {
      String redactedUrl = new URL(originalRequest.url(), "/...").toString();
      return string + " to " + redactedUrl;
    } catch (MalformedURLException e) {
      return string;
    }
  }

  private Response getResponseWithInterceptorChain() throws IOException {
    return new RealInterceptorChain(0, originalRequest).proceed(originalRequest);
  }

  class RealInterceptorChain implements Interceptor.Chain {
    private final int index;
    private final Request request;

    RealInterceptorChain(int index, Request request) {
      this.index = index;
      this.request = request;
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      if (index < client.interceptors().size()) {
        // There's another interceptor in the chain. Call that.
        RealInterceptorChain chain = new RealInterceptorChain(index + 1, request);
        return client.interceptors().get(index).intercept(chain);
      } else {
        // No more interceptors. Do HTTP.
        return getResponse(request, false);
      }
    }
  }

  /**
   * Performs the request and returns the response. May return null if this
   * call was canceled.
   */
  Response getResponse(Request request, boolean forWebSocket) throws IOException {
    // Copy body metadata to the appropriate request headers.
    RequestBody body = request.body();
    if (body != null) {
      Request.Builder requestBuilder = request.newBuilder();

      MediaType contentType = body.contentType();
      if (contentType != null) {
        requestBuilder.header("Content-Type", contentType.toString());
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }

      request = requestBuilder.build();
    }

    // Create the initial HTTP engine. Retries and redirects need new engine for each attempt.
    engine = new HttpEngine(client, request, false, null, null, null, null);

    int redirectionCount = 0;
    while (true) {
      if (canceled) {
        engine.releaseConnection();
        return null;
      }

      try {
        engine.sendRequest();

        if (request.body() != null) {
          BufferedSink sink = engine.getBufferedRequestBody();
          request.body().writeTo(sink);
        }

        engine.readResponse(forWebSocket);
      } catch (IOException e) {
        HttpEngine retryEngine = engine.recover(e, null);
        if (retryEngine != null) {
          engine = retryEngine;
          continue;
        }

        // Give up; recovery is not possible.
        throw e;
      }

      Response response = engine.getResponse();
      Request followUp = engine.followUpRequest();

      if (followUp == null) {
        Response.Builder builder = response.newBuilder();
        if (!forWebSocket) {
          engine.releaseConnection();
          builder.body(new RealResponseBody(response, engine.getResponseBody()));
        }
        return builder.build();
      }

      if (engine.getResponse().isRedirect() && ++redirectionCount > MAX_REDIRECTS) {
        throw new ProtocolException("Too many redirects: " + redirectionCount);
      }

      if (!engine.sameConnection(followUp.url())) {
        engine.releaseConnection();
      }

      Connection connection = engine.close();
      request = followUp;
      engine = new HttpEngine(client, request, false, connection, null, null, response);
    }
  }

  private static class RealResponseBody extends ResponseBody {
    private final Response response;
    private final BufferedSource source;

    RealResponseBody(Response response, BufferedSource source) {
      this.response = response;
      this.source = source;
    }

    @Override public MediaType contentType() {
      String contentType = response.header("Content-Type");
      return contentType != null ? MediaType.parse(contentType) : null;
    }

    @Override public long contentLength() {
      return OkHeaders.contentLength(response);
    }

    @Override public BufferedSource source() {
      return source;
    }
  }
}
