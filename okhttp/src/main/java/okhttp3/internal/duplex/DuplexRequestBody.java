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
package okhttp3.internal.duplex;

import java.io.IOException;
import java.io.InterruptedIOException;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;
import okio.Sink;

/**
 * Duplex request bodies are special. They are called differently and they yield different
 * interaction patterns over the network.
 *
 * <p>Rather than writing the body contents via the {@link #writeTo} callback, callers stream
 * request bodies by writing to the {@link #createSink sink}. Callers stream out the entire request
 * body and then {@link Sink#close close} it to signal the end of the request stream. The {@code
 * maxBufferSize} controls how many outbound bytes may be enqueued without blocking: large values
 * allow writing without blocking; small values limit a call’s memory consumption. 64 KiB is a
 * reasonable size for most applications.
 *
 * <p>Calls with duplex bodies may receive HTTP responses before the HTTP request body has
 * completed
 * streaming. Interleaving of request and response data is why this mechanism is called duplex.
 * Though any call may be initiated as a duplex call, only web servers that are specially designed
 * for this nonstandard interaction will use it. As of 2019-01, the only widely-used implementation
 * of this pattern is gRPC.
 *
 * <p>Duplex calls are only supported for HTTP/2 connections. Calls to HTTP/1 servers will fail
 * before the HTTP request is transmitted.
 *
 * <p>Duplex calls may not be used with OkHttp interceptors that log, compress, encrypt, or
 * otherwise access the request body. This includes OkHttp’s
 * {@link okhttp3.logging.HttpLoggingInterceptor logging interceptor}.
 */
public final class DuplexRequestBody extends RequestBody implements Callback {
  private final Pipe pipe;
  private final @Nullable MediaType contentType;
  private @Nullable IOException failure;
  private @Nullable Response response;
  private boolean enqueued;

  // TODO(jwilson/oldergod): include content-length? Callers might know it!
  public DuplexRequestBody(@Nullable MediaType contentType, long pipeMaxBufferSize) {
    this.pipe = new Pipe(pipeMaxBufferSize);
    this.contentType = contentType;
  }

  public BufferedSink createSink(Call call) {
    call.enqueue(this);
    enqueued = true;
    return Okio.buffer(pipe.sink());
  }

  public void foldSink(Sink requestBodyOut) throws IOException {
    pipe.fold(requestBodyOut);
  }

  @Override public synchronized void onFailure(Call call, IOException e) {
    if (this.failure != null || this.response != null) throw new IllegalStateException();
    this.failure = e;
    notifyAll();
  }

  @Override public synchronized void onResponse(Call call, Response response) {
    if (this.failure != null || this.response != null) throw new IllegalStateException();
    this.response = response;
    notifyAll();
  }

  public synchronized Response awaitExecute() throws IOException {
    if (!enqueued) throw new IllegalStateException("body isn't enqueued.");
    try {
      while (failure == null && response == null) {
        wait();
      }
      if (failure != null) throw failure;
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Retain interrupted status.
      throw new InterruptedIOException();
    }
  }

  @Override public @Nullable MediaType contentType() {
    return contentType;
  }

  @Override public void writeTo(BufferedSink sink) {
    throw new UnsupportedOperationException();
  }
}
