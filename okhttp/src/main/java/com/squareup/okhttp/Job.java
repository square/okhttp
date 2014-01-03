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

import com.squareup.okhttp.internal.http.HttpAuthenticator;
import com.squareup.okhttp.internal.http.HttpEngine;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;

import static com.squareup.okhttp.internal.Util.getEffectivePort;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_MOVED_PERM;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_MOVED_TEMP;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_MULT_CHOICE;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_PROXY_AUTH;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_SEE_OTHER;
import static com.squareup.okhttp.internal.http.HttpURLConnectionImpl.HTTP_UNAUTHORIZED;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

final class Job implements Runnable {
  private final Dispatcher dispatcher;
  private final OkHttpClient client;
  private final Response.Receiver responseReceiver;

  /** The request; possibly a consequence of redirects or auth headers. */
  private Request request;

  public Job(Dispatcher dispatcher, OkHttpClient client, Request request,
      Response.Receiver responseReceiver) {
    this.dispatcher = dispatcher;
    this.client = client;
    this.request = request;
    this.responseReceiver = responseReceiver;
  }

  Object tag() {
    return request.tag();
  }

  @Override public void run() {
    try {
      Response response = execute();
      responseReceiver.onResponse(response);
    } catch (IOException e) {
      responseReceiver.onFailure(new Failure.Builder()
          .request(request)
          .exception(e)
          .build());
    } finally {
      // TODO: close the response body
      // TODO: release the HTTP engine (potentially multiple!)
      dispatcher.finished(this);
    }
  }

  private Response execute() throws IOException {
    Connection connection = null;
    Response redirectedBy = null;

    while (true) {
      Request.Body body = request.body();
      if (body != null) {
        MediaType contentType = body.contentType();
        if (contentType == null) throw new IllegalStateException("contentType == null");

        Request.Builder requestBuilder = request.newBuilder();
        requestBuilder.header("Content-Type", contentType.toString());

        long contentLength = body.contentLength();
        if (contentLength != -1) {
          requestBuilder.setContentLength(contentLength);
          requestBuilder.removeHeader("Transfer-Encoding");
        } else {
          requestBuilder.header("Transfer-Encoding", "chunked");
          requestBuilder.removeHeader("Content-Length");
        }

        request = requestBuilder.build();
      }

      HttpEngine engine = newEngine(connection);
      engine.sendRequest();

      if (body != null) {
        body.writeTo(engine.getRequestBody());
      }

      engine.readResponse();

      Response response = engine.getResponse();
      Request redirect = processResponse(engine, response);

      if (redirect == null) {
        engine.automaticallyReleaseConnectionToPool();
        return response.newBuilder()
            .body(new Dispatcher.RealResponseBody(response, engine.getResponseBody()))
            .redirectedBy(redirectedBy)
            .build();
      }

      // TODO: fail if too many redirects
      // TODO: fail if not following redirects
      engine.release(false);

      connection = sameConnection(request, redirect) ? engine.getConnection() : null;
      redirectedBy = response.newBuilder().redirectedBy(redirectedBy).build(); // Chained.
      request = redirect;
    }
  }

  HttpEngine newEngine(Connection connection) throws IOException {
    return new HttpEngine(client, request, false, connection, null);
  }

  /**
   * Figures out the HTTP request to make in response to receiving {@code
   * response}. This will either add authentication headers or follow
   * redirects. If a follow-up is either unnecessary or not applicable, this
   * returns null.
   */
  private Request processResponse(HttpEngine engine, Response response) throws IOException {
    Request request = response.request();
    Proxy selectedProxy = engine.getRoute() != null
        ? engine.getRoute().getProxy()
        : client.getProxy();
    int responseCode = response.code();

    switch (responseCode) {
      case HTTP_PROXY_AUTH:
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        // fall-through
      case HTTP_UNAUTHORIZED:
        return HttpAuthenticator.processAuthHeader(
            client.getAuthenticator(), response, selectedProxy);

      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
      case HTTP_TEMP_REDIRECT:
        String method = request.method();
        if (responseCode == HTTP_TEMP_REDIRECT && !method.equals("GET") && !method.equals("HEAD")) {
          // "If the 307 status code is received in response to a request other than GET or HEAD,
          // the user agent MUST NOT automatically redirect the request"
          return null;
        }

        String location = response.header("Location");
        if (location == null) {
          return null;
        }

        URL url = new URL(request.url(), location);
        if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) {
          return null; // Don't follow redirects to unsupported protocols.
        }

        return this.request.newBuilder().url(url).build();

      default:
        return null;
    }
  }

  static boolean sameConnection(Request a, Request b) {
    return a.url().getHost().equals(b.url().getHost())
        && getEffectivePort(a.url()) == getEffectivePort(b.url())
        && a.url().getProtocol().equals(b.url().getProtocol());
  }
}
