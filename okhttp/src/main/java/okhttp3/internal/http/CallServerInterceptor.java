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
import java.util.ArrayList;
import java.util.List;
import okhttp3.Headers;
import okhttp3.HttpSink;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Codec;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/** This is the last interceptor in the chain. It makes a network call to the server. */
public final class CallServerInterceptor implements Interceptor {
  private final boolean forWebSocket;

  public CallServerInterceptor(boolean forWebSocket) {
    this.forWebSocket = forWebSocket;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    final RealInterceptorChain realChain = (RealInterceptorChain) chain;
    final HttpCodec httpCodec = realChain.httpStream();
    StreamAllocation streamAllocation = realChain.streamAllocation();
    RealConnection connection = (RealConnection) realChain.connection();
    Request request = realChain.request();

    long sentRequestMillis = System.currentTimeMillis();

    realChain.eventListener().requestHeadersStart(realChain.call());
    httpCodec.writeRequestHeaders(request);
    realChain.eventListener().requestHeadersEnd(realChain.call(), request);

    HttpSink httpSink = null;
    Response.Builder responseBuilder = null;
    if (HttpMethod.permitsRequestBody(request.method())
        && (request.body() != null || request.isDuplex())) {
      // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
      // Continue" response before transmitting the request body. If we don't get that, return
      // what we did get (such as a 4xx response) without ever transmitting the request body.
      if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
        httpCodec.flushRequest();
        realChain.eventListener().responseHeadersStart(realChain.call());
        responseBuilder = httpCodec.readResponseHeaders(true);
      }

      if (responseBuilder == null) {
        if (request.isDuplex()) {
          // Prepare a duplex body so that the application can send a request body later.
          final CountingSink requestBodyOut =
              new CountingSink(httpCodec.createRequestBody(request, -1L));
          final BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);
          httpSink = new HttpSink() {
            @Override public BufferedSink sink() {
              return bufferedRequestBody;
            }

            @Override public void headers(Headers headers) throws IOException {
              List<Header> headerBlock = new ArrayList<>(headers.size() / 2);
              for (int i = 0, size = headers.size(); i < size; i++) {
                headerBlock.add(new Header(headers.name(i), headers.value(i)));
              }
              ((Http2Codec) httpCodec).writeRequestHeaders(headerBlock);
            }

            @Override public void close() throws IOException {
              bufferedRequestBody.close();
              realChain.eventListener()
                  .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
            }
          };
        } else {
          // Write the request body if the "Expect: 100-continue" expectation was met.
          realChain.eventListener().requestBodyStart(realChain.call());
          long contentLength = request.body().contentLength();
          CountingSink requestBodyOut =
              new CountingSink(httpCodec.createRequestBody(request, contentLength));
          BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);

          request.body().writeTo(bufferedRequestBody);
          bufferedRequestBody.close();
          realChain.eventListener()
              .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
        }
      } else if (!connection.isMultiplexed()) {
        // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
        // from being reused. Otherwise we're still obligated to transmit the request body to
        // leave the connection in a consistent state.
        streamAllocation.noNewStreams();
      }
    }

    if (request.isDuplex()) {
      httpCodec.flushRequest();
    } else {
      httpCodec.finishRequest();
    }

    if (responseBuilder == null) {
      realChain.eventListener().responseHeadersStart(realChain.call());
      responseBuilder = httpCodec.readResponseHeaders(false);
    }

    Response response = responseBuilder
        .request(request)
        .handshake(streamAllocation.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .httpSink(httpSink)
        .build();

    int code = response.code();
    if (code == 100) {
      // server sent a 100-continue even though we did not request one.
      // try again to read the actual response
      responseBuilder = httpCodec.readResponseHeaders(false);

      response = responseBuilder
          .request(request)
          .handshake(streamAllocation.connection().handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .httpSink(httpSink)
          .build();

      code = response.code();
    }

    if (request.isDuplex()) {
      Response.Builder builder = response.newBuilder();
      Internal.instance.setHttp2Codec(builder, (Http2Codec) httpCodec);
      response = builder.build();
    }

    realChain.eventListener()
            .responseHeadersEnd(realChain.call(), response);

    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      response = response.newBuilder()
          .body(httpCodec.openResponseBody(response))
          .build();
    }

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      streamAllocation.noNewStreams();
    }

    if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
      throw new ProtocolException(
          "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
    }

    return response;
  }

  static final class CountingSink extends ForwardingSink {
    long successfulCount;

    CountingSink(Sink delegate) {
      super(delegate);
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      super.write(source, byteCount);
      successfulCount += byteCount;
    }
  }
}
