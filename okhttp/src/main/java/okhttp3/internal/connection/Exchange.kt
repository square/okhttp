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
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketException;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.http.ExchangeCodec;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.ws.RealWebSocket;
import okio.Buffer;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

/**
 * Transmits a single HTTP request and a response pair. This layers connection management and events
 * on {@link ExchangeCodec}, which handles the actual I/O.
 */
public final class Exchange {
  final Transmitter transmitter;
  final Call call;
  final EventListener eventListener;
  final ExchangeFinder finder;
  final ExchangeCodec codec;
  private boolean duplex;

  public Exchange(Transmitter transmitter, Call call, EventListener eventListener,
      ExchangeFinder finder, ExchangeCodec codec) {
    this.transmitter = transmitter;
    this.call = call;
    this.eventListener = eventListener;
    this.finder = finder;
    this.codec = codec;
  }

  public RealConnection connection() {
    return codec.connection();
  }

  /** Returns true if the request body need not complete before the response body starts. */
  public boolean isDuplex() {
    return duplex;
  }

  public void writeRequestHeaders(Request request) throws IOException {
    try {
      eventListener.requestHeadersStart(call);
      codec.writeRequestHeaders(request);
      eventListener.requestHeadersEnd(call, request);
    } catch (IOException e) {
      eventListener.requestFailed(call, e);
      trackFailure(e);
      throw e;
    }
  }

  public Sink createRequestBody(Request request, boolean duplex) throws IOException {
    this.duplex = duplex;
    long contentLength = request.body().contentLength();
    eventListener.requestBodyStart(call);
    Sink rawRequestBody = codec.createRequestBody(request, contentLength);
    return new RequestBodySink(rawRequestBody, contentLength);
  }

  public void flushRequest() throws IOException {
    try {
      codec.flushRequest();
    } catch (IOException e) {
      eventListener.requestFailed(call, e);
      trackFailure(e);
      throw e;
    }
  }

  public void finishRequest() throws IOException {
    try {
      codec.finishRequest();
    } catch (IOException e) {
      eventListener.requestFailed(call, e);
      trackFailure(e);
      throw e;
    }
  }

  public void responseHeadersStart() {
    eventListener.responseHeadersStart(call);
  }

  public @Nullable Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    try {
      Response.Builder result = codec.readResponseHeaders(expectContinue);
      if (result != null) {
        Internal.instance.initExchange(result, this);
      }
      return result;
    } catch (IOException e) {
      eventListener.responseFailed(call, e);
      trackFailure(e);
      throw e;
    }
  }

  public void responseHeadersEnd(Response response) {
    eventListener.responseHeadersEnd(call, response);
  }

  public ResponseBody openResponseBody(Response response) throws IOException {
    try {
      eventListener.responseBodyStart(call);
      String contentType = response.header("Content-Type");
      long contentLength = codec.reportedContentLength(response);
      Source rawSource = codec.openResponseBodySource(response);
      ResponseBodySource source = new ResponseBodySource(rawSource, contentLength);
      return new RealResponseBody(contentType, contentLength, Okio.buffer(source));
    } catch (IOException e) {
      eventListener.responseFailed(call, e);
      trackFailure(e);
      throw e;
    }
  }

  public Headers trailers() throws IOException {
    return codec.trailers();
  }

  public void timeoutEarlyExit() {
    transmitter.timeoutEarlyExit();
  }

  public RealWebSocket.Streams newWebSocketStreams() throws SocketException {
    transmitter.timeoutEarlyExit();
    return codec.connection().newWebSocketStreams(this);
  }

  public void webSocketUpgradeFailed() {
    bodyComplete(-1L, true, true, null);
  }

  public void noNewExchangesOnConnection() {
    codec.connection().noNewExchanges();
  }

  public void cancel() {
    codec.cancel();
  }

  /**
   * Revoke this exchange's access to streams. This is necessary when a follow-up request is
   * required but the preceding exchange hasn't completed yet.
   */
  public void detachWithViolence() {
    codec.cancel();
    transmitter.exchangeMessageDone(this, true, true, null);
  }

  void trackFailure(IOException e) {
    finder.trackFailure();
    codec.connection().trackFailure(e);
  }

  @Nullable IOException bodyComplete(
      long bytesRead, boolean responseDone, boolean requestDone, @Nullable IOException e) {
    if (e != null) {
      trackFailure(e);
    }
    if (requestDone) {
      if (e != null) {
        eventListener.requestFailed(call, e);
      } else {
        eventListener.requestBodyEnd(call, bytesRead);
      }
    }
    if (responseDone) {
      if (e != null) {
        eventListener.responseFailed(call, e);
      } else {
        eventListener.responseBodyEnd(call, bytesRead);
      }
    }
    return transmitter.exchangeMessageDone(this, requestDone, responseDone, e);
  }

  public void noRequestBody() {
    transmitter.exchangeMessageDone(this, true, false, null);
  }

  /** A request body that fires events when it completes. */
  private final class RequestBodySink extends ForwardingSink {
    private boolean completed;
    /** The exact number of bytes to be written, or -1L if that is unknown. */
    private long contentLength;
    private long bytesReceived;
    private boolean closed;

    RequestBodySink(Sink delegate, long contentLength) {
      super(delegate);
      this.contentLength = contentLength;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
        throw new ProtocolException("expected " + contentLength
            + " bytes but received " + (bytesReceived + byteCount));
      }
      try {
        super.write(source, byteCount);
        this.bytesReceived += byteCount;
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void flush() throws IOException {
      try {
        super.flush();
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (contentLength != -1L && bytesReceived != contentLength) {
        throw new ProtocolException("unexpected end of stream");
      }
      try {
        super.close();
        complete(null);
      } catch (IOException e) {
        throw complete(e);
      }
    }

    private @Nullable IOException complete(@Nullable IOException e) {
      if (completed) return e;
      completed = true;
      return bodyComplete(bytesReceived, false, true, e);
    }
  }

  /** A response body that fires events when it completes. */
  final class ResponseBodySource extends ForwardingSource {
    private final long contentLength;
    private long bytesReceived;
    private boolean completed;
    private boolean closed;

    ResponseBodySource(Source delegate, long contentLength) {
      super(delegate);
      this.contentLength = contentLength;

      if (contentLength == 0L) {
        complete(null);
      }
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      try {
        long read = delegate().read(sink, byteCount);
        if (read == -1L) {
          complete(null);
          return -1L;
        }

        long newBytesReceived = bytesReceived + read;
        if (contentLength != -1L && newBytesReceived > contentLength) {
          throw new ProtocolException("expected " + contentLength
              + " bytes but received " + newBytesReceived);
        }

        bytesReceived = newBytesReceived;
        if (newBytesReceived == contentLength) {
          complete(null);
        }

        return read;
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        super.close();
        complete(null);
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Nullable IOException complete(@Nullable IOException e) {
      if (completed) return e;
      completed = true;
      return bodyComplete(bytesReceived, true, false, e);
    }
  }
}
