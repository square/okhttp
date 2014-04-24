/*
 * Copyright (C) 2013 Square, Inc.
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
import java.net.ProtocolException;
import java.util.concurrent.CancellationException;
import okio.BufferedSink;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.http.HttpEngine.MAX_REDIRECTS;

final class Job extends NamedRunnable {
  private final Dispatcher dispatcher;
  private final OkHttpClient client;
  private final Response.Receiver responseReceiver;
  private int redirectionCount;

  volatile boolean canceled;

  /** The request; possibly a consequence of redirects or auth headers. */
  private Request request;
  HttpEngine engine;

  public Job(Dispatcher dispatcher, OkHttpClient client, Request request,
      Response.Receiver responseReceiver) {
    super("OkHttp %s", request.urlString());
    this.dispatcher = dispatcher;
    this.client = client;
    this.request = request;
    this.responseReceiver = responseReceiver;
  }

  String host() {
    return request.url().getHost();
  }

  Request request() {
    return request;
  }

  Object tag() {
    return request.tag();
  }

  @Override protected void execute() {
    boolean signalledReceiver = false;
    try {
      Response response = getResponse();
      if (canceled) {
        signalledReceiver = true;
        responseReceiver.onFailure(new Failure.Builder()
            .request(request)
            .exception(new CancellationException("Canceled"))
            .build());
      } else {
        signalledReceiver = true;
        responseReceiver.onResponse(response);
      }
    } catch (IOException e) {
      if (signalledReceiver) return; // Do not signal the receiver twice!
      responseReceiver.onFailure(new Failure.Builder()
          .request(request)
          .exception(e)
          .build());
    } finally {
      engine.close(); // Close the connection if it isn't already.
      dispatcher.finished(this);
    }
  }

  /**
   * Performs the request and returns the response. May return null if this job
   * was canceled.
   */
  Response getResponse() throws IOException {
    Response redirectedBy = null;

    // Copy body metadata to the appropriate request headers.
    Request.Body body = request.body();
    if (body != null) {
      MediaType contentType = body.contentType();
      if (contentType == null) throw new IllegalStateException("contentType == null");

      Request.Builder requestBuilder = request.newBuilder();
      requestBuilder.header("Content-Type", contentType.toString());

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
    engine = new HttpEngine(client, request, false, null, null, null);

    while (true) {
      if (canceled) return null;

      try {
        engine.sendRequest();

        if (body != null) {
          BufferedSink sink = engine.getBufferedRequestBody();
          body.writeTo(sink);
          sink.flush();
        }

        engine.readResponse();
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
        engine.releaseConnection();
        return response.newBuilder()
            .body(new RealResponseBody(response, engine.getResponseBody()))
            .redirectedBy(redirectedBy)
            .build();
      }

      if (engine.getResponse().isRedirect() && ++redirectionCount > MAX_REDIRECTS) {
        throw new ProtocolException("Too many redirects: " + redirectionCount);
      }

      // TODO: drop from POST to GET when redirected? HttpURLConnection does.
      // TODO: confirm that Cookies are not retained across hosts.

      if (!engine.sameConnection(followUp)) {
        engine.releaseConnection();
      }

      Connection connection = engine.close();
      redirectedBy = response.newBuilder().redirectedBy(redirectedBy).build(); // Chained.
      request = followUp;
      engine = new HttpEngine(client, request, false, connection, null, null);
    }
  }

  static class RealResponseBody extends Response.Body {
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
