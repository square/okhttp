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
import okhttp3.internal.Internal;
import okhttp3.internal.Transmitter;
import okhttp3.internal.Util;
import okhttp3.internal.duplex.DuplexRequestBody;
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
    Transmitter transmitter = realChain.transmitter();
    Request request = realChain.request();

    long sentRequestMillis = System.currentTimeMillis();

    transmitter.writeRequestHeaders(request);

    Response.Builder responseBuilder = null;
    if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
      // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
      // Continue" response before transmitting the request body. If we don't get that, return
      // what we did get (such as a 4xx response) without ever transmitting the request body.
      if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
        transmitter.flushRequest();
        responseBuilder = transmitter.readResponseHeaders(true);
      }

      if (responseBuilder == null) {
        if (request.body() instanceof DuplexRequestBody) {
          // Prepare a duplex body so that the application can send a request body later.
          transmitter.flushRequest();
          BufferedSink bufferedRequestBody = Okio.buffer(transmitter.createRequestBody(request));
          request.body().writeTo(bufferedRequestBody);
        } else {
          // Write the request body if the "Expect: 100-continue" expectation was met.
          BufferedSink bufferedRequestBody = Okio.buffer(transmitter.createRequestBody(request));
          request.body().writeTo(bufferedRequestBody);
          bufferedRequestBody.close();
        }
      } else if (!transmitter.isConnectionMultiplexed()) {
        // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
        // from being reused. Otherwise we're still obligated to transmit the request body to
        // leave the connection in a consistent state.
        transmitter.noNewStreams();
      }
    }

    if (!(request.body() instanceof DuplexRequestBody)) {
      transmitter.finishRequest();
    }

    if (responseBuilder == null) {
      responseBuilder = transmitter.readResponseHeaders(false);
    }

    responseBuilder
        .request(request)
        .handshake(transmitter.handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis());
    Internal.instance.initDeferredTrailers(responseBuilder, transmitter.deferredTrailers());
    Response response = responseBuilder.build();

    int code = response.code();
    if (code == 100) {
      // server sent a 100-continue even though we did not request one.
      // try again to read the actual response
      responseBuilder = transmitter.readResponseHeaders(false);

      responseBuilder
          .request(request)
          .handshake(transmitter.handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis());
      Internal.instance.initDeferredTrailers(responseBuilder, transmitter.deferredTrailers());
      response = responseBuilder.build();

      code = response.code();
    }

    transmitter.responseHeadersEnd(response);

    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      response = response.newBuilder()
          .body(transmitter.openResponseBody(response))
          .build();
    }

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      transmitter.noNewStreams();
    }

    if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
      throw new ProtocolException(
          "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
    }

    return response;
  }
}
