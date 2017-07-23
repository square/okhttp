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
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

/** This is the last interceptor in the chain. It makes a network call to the server. */
public final class CallServerInterceptor implements Interceptor {
  private final boolean forWebSocket;

  public CallServerInterceptor(boolean forWebSocket) {
    this.forWebSocket = forWebSocket;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    HttpCodec httpCodec = realChain.httpStream();
    StreamAllocation streamAllocation = realChain.streamAllocation();
    RealConnection connection = (RealConnection) realChain.connection();
    Request request = realChain.request();

    long sentRequestMillis = System.currentTimeMillis();

    realChain.eventListener().requestHeadersStart(realChain.call());
    try {
      httpCodec.writeRequestHeaders(request);
      realChain.eventListener().requestHeadersEnd(realChain.call(), null);
    } catch (IOException ioe) {
      realChain.eventListener().requestHeadersEnd(realChain.call(), ioe);
      throw ioe;
    }

    Response.Builder responseBuilder = null;
    if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
      realChain.eventListener().requestBodyStart(realChain.call());
      try {
        // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
        // Continue" response before transmitting the request body. If we don't get that, return
        // what we did get (such as a 4xx response) without ever transmitting the request body.
        if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
          httpCodec.flushRequest();
          // TODO event listener
          responseBuilder = httpCodec.readResponseHeaders(true);
        }

        if (responseBuilder == null) {
          // Write the request body if the "Expect: 100-continue" expectation was met.
          Sink requestBodyOut =
              httpCodec.createRequestBody(request, request.body().contentLength());
          BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);

          request.body().writeTo(bufferedRequestBody);
          bufferedRequestBody.close();
        } else if (!connection.isMultiplexed()) {
          // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
          // from being reused. Otherwise we're still obligated to transmit the request body to
          // leave the connection in a consistent state.
          streamAllocation.noNewStreams();
        }
        realChain.eventListener().requestBodyEnd(realChain.call(), null);
      } catch (IOException ioe) {
        realChain.eventListener().requestBodyEnd(realChain.call(), ioe);
        throw ioe;
      }
    }

    httpCodec.finishRequest();

    if (responseBuilder == null) {
      realChain.eventListener().responseHeadersStart(realChain.call());
      try {
        responseBuilder = httpCodec.readResponseHeaders(false);
        realChain.eventListener().responseHeadersEnd(realChain.call(), null);
      } catch (IOException ioe) {
        realChain.eventListener().responseHeadersEnd(realChain.call(), ioe);
        throw ioe;
      }
    }

    Response response = responseBuilder
        .request(request)
        .handshake(streamAllocation.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      streamAllocation.noNewStreams();
    }

    int code = response.code();
    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      realChain.eventListener().responseBodyStart(realChain.call());
      try {
        response = response.newBuilder()
            .body(httpCodec.openResponseBody(response))
            .build();

        if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
          throw new ProtocolException(
              "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }

        realChain.eventListener().responseBodyEnd(realChain.call(), null);
      } catch (IOException ioe) {
        realChain.eventListener().responseBodyEnd(realChain.call(), ioe);
        throw ioe;
      }
    }

    return response;
  }
}
