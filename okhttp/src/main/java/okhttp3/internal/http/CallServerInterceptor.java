/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.Exchange;
import okio.BufferedSink;
import okio.Okio;

/** This is the last interceptor in the chain. It makes a network call to the server. */
public final class CallServerInterceptor implements Interceptor {
  private final boolean forWebSocket;

  public CallServerInterceptor(boolean forWebSocket) {
    this.forWebSocket = forWebSocket;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Exchange exchange = realChain.exchange();
    Request request = realChain.request();

    long sentRequestMillis = System.currentTimeMillis();

    exchange.writeRequestHeaders(request);

    boolean responseHeadersStarted = false;
    Response.Builder responseBuilder = null;
    if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
      // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
      // Continue" response before transmitting the request body. If we don't get that, return
      // what we did get (such as a 4xx response) without ever transmitting the request body.
      if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
        exchange.flushRequest();
        responseHeadersStarted = true;
        exchange.responseHeadersStart();
        responseBuilder = exchange.readResponseHeaders(true);
      }

      if (responseBuilder == null) {
        if (request.body().isDuplex()) {
          // Prepare a duplex body so that the application can send a request body later.
          exchange.flushRequest();
          BufferedSink bufferedRequestBody = Okio.buffer(
              exchange.createRequestBody(request, true));
          request.body().writeTo(bufferedRequestBody);
        } else {
          // Write the request body if the "Expect: 100-continue" expectation was met.
          BufferedSink bufferedRequestBody = Okio.buffer(
              exchange.createRequestBody(request, false));
          request.body().writeTo(bufferedRequestBody);
          bufferedRequestBody.close();
        }
      } else {
        exchange.noRequestBody();
        if (!exchange.connection().isMultiplexed()) {
          // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
          // from being reused. Otherwise we're still obligated to transmit the request body to
          // leave the connection in a consistent state.
          exchange.noNewExchangesOnConnection();
        }
      }
    } else {
      exchange.noRequestBody();
    }

    if (request.body() == null || !request.body().isDuplex()) {
      exchange.finishRequest();
    }

    if (!responseHeadersStarted) {
      exchange.responseHeadersStart();
    }

    if (responseBuilder == null) {
      responseBuilder = exchange.readResponseHeaders(false);
    }

    Response response = responseBuilder
        .request(request)
        .handshake(exchange.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();

    int code = response.code();
    if (code == 100) {
      // server sent a 100-continue even though we did not request one.
      // try again to read the actual response
      response = exchange.readResponseHeaders(false)
          .request(request)
          .handshake(exchange.connection().handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();

      code = response.code();
    }

    exchange.responseHeadersEnd(response);

    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      response = response.newBuilder()
          .body(exchange.openResponseBody(response))
          .build();
    }

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      exchange.noNewExchangesOnConnection();
    }

    if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
      throw new ProtocolException(
          "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
    }

    return response;
  }
}
