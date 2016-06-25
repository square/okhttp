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
package okhttp3;

import java.io.IOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.Platform;
import okhttp3.internal.http.HttpEngine;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RouteException;
import okhttp3.internal.http.StreamAllocation;
import okhttp3.internal.http.UnrepeatableRequestBody;

import static okhttp3.internal.Platform.INFO;
import static okhttp3.internal.http.HttpEngine.MAX_FOLLOW_UPS;

final class RealCall implements Call {
  private final OkHttpClient client;

  // Guarded by this.
  private boolean executed;
  volatile boolean canceled;
  private boolean forWebSocket;

  /** The application's original request unadulterated by redirects or auth headers. */
  Request originalRequest;
  HttpEngine engine;

  protected RealCall(OkHttpClient client, Request originalRequest) {
    this.client = client;
    this.originalRequest = originalRequest;
  }

  @Override public Request request() {
    return originalRequest;
  }

  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    try {
      client.dispatcher().executed(this);
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  synchronized void setForWebSocket() {
    if (executed) throw new IllegalStateException("Already Executed");
    this.forWebSocket = true;
  }

  @Override public void enqueue(Callback responseCallback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override public void cancel() {
    canceled = true;
    if (engine != null) engine.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return canceled;
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    private AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl().toString());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    void cancel() {
      RealCall.this.cancel();
    }

    RealCall get() {
      return RealCall.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain();
        if (canceled) {
          signalledCallback = true;
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = canceled ? "canceled call" : "call";
    return string + " to " + redactedUrl();
  }

  HttpUrl redactedUrl() {
    return originalRequest.url().resolve("/...");
  }

  private Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(new RetryAndFollowUpInterceptor());
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new HttpEngine.CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(
        interceptors, null, null, null, 0, originalRequest);
    return chain.proceed(originalRequest);
  }

  /**
   * Performs the request and returns the response. May throw if this call was canceled. This isn't
   * a regular interceptor because it doesn't delegate to the chain.
   */
  class RetryAndFollowUpInterceptor implements Interceptor {
    @Override public Response intercept(Chain chain) throws IOException {
      Request request = chain.request();

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
      engine = new HttpEngine(client, request.url(), forWebSocket, null, null);

      int followUpCount = 0;
      while (true) {
        if (canceled) {
          engine.releaseStreamAllocation();
          throw new IOException("Canceled");
        }

        Response response = null;
        boolean releaseConnection = true;
        try {
          response = engine.proceed(request, (RealInterceptorChain) chain);
          releaseConnection = false;
        } catch (RouteException e) {
          // The attempt to connect via a route failed. The request will not have been sent.
          HttpEngine retryEngine = engine.recover(e.getLastConnectException(), true, request);
          if (retryEngine != null) {
            releaseConnection = false;
            engine = retryEngine;
            continue;
          }
          // Give up; recovery is not possible.
          throw e.getLastConnectException();
        } catch (IOException e) {
          // An attempt to communicate with a server failed. The request may have been sent.
          HttpEngine retryEngine = engine.recover(e, false, request);
          if (retryEngine != null) {
            releaseConnection = false;
            engine = retryEngine;
            continue;
          }

          // Give up; recovery is not possible.
          throw e;
        } finally {
          // We're throwing an unchecked exception. Release any resources.
          if (releaseConnection) {
            StreamAllocation streamAllocation = engine.close(null);
            streamAllocation.release();
          }
        }

        Request followUp = engine.followUpRequest(response);

        if (followUp == null) {
          if (!forWebSocket) {
            engine.releaseStreamAllocation();
          }
          return response;
        }

        StreamAllocation streamAllocation = engine.close(response);

        if (++followUpCount > MAX_FOLLOW_UPS) {
          streamAllocation.release();
          throw new ProtocolException("Too many follow-up requests: " + followUpCount);
        }

        if (followUp.body() instanceof UnrepeatableRequestBody) {
          throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
        }

        if (!engine.sameConnection(response, followUp.url())) {
          streamAllocation.release();
          streamAllocation = null;
        } else if (streamAllocation.stream() != null) {
          throw new IllegalStateException("Closing the body of " + response
              + " didn't close its backing stream. Bad interceptor?");
        }

        request = followUp;
        engine = new HttpEngine(client, request.url(), forWebSocket, streamAllocation,
            response);
      }
    }
  }
}
