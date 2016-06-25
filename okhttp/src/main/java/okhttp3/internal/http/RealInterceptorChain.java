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
import java.util.List;
import okhttp3.Connection;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 */
public final class RealInterceptorChain implements Interceptor.Chain {
  private final List<Interceptor> interceptors;
  private final Connection connection;
  private final StreamAllocation streamAllocation;
  private final HttpStream httpStream;
  private final int index;
  private final Request request;
  private int calls;

  public RealInterceptorChain(List<Interceptor> interceptors, Connection connection,
      StreamAllocation streamAllocation, HttpStream httpStream, int index, Request request) {
    this.interceptors = interceptors;
    this.connection = connection;
    this.streamAllocation = streamAllocation;
    this.httpStream = httpStream;
    this.index = index;
    this.request = request;
  }

  @Override public Connection connection() {
    return connection;
  }

  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }

  public HttpStream httpStream() {
    return httpStream;
  }

  @Override public Request request() {
    return request;
  }

  @Override public Response proceed(Request request) throws IOException {
    return proceed(request, connection, streamAllocation, httpStream);
  }

  public Response proceed(Request request, Connection connection, StreamAllocation streamAllocation,
      HttpStream httpStream) throws IOException {
    if (index >= interceptors.size()) throw new AssertionError();

    calls++;

    // If we already have a connection, confirm that the incoming request will use it.
    if (this.connection != null && !sameConnection(request.url())) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must retain the same host and port");
    }

    // If we already have a connection, confirm that this is the only call to chain.proceed().
    if (this.connection != null && calls > 1) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must call proceed() exactly once");
    }

    // Call the next interceptor in the chain.
    RealInterceptorChain chain = new RealInterceptorChain(
        interceptors, connection, streamAllocation, httpStream, index + 1, request);
    Interceptor interceptor = interceptors.get(index);
    Response interceptedResponse = interceptor.intercept(chain);

    // Confirm that the next interceptor made its required call to chain.proceed().
    if (connection != null && index + 1 < interceptors.size() && chain.calls != 1) {
      throw new IllegalStateException("network interceptor " + interceptor
          + " must call proceed() exactly once");
    }

    // Confirm that the intercepted response isn't null.
    if (interceptedResponse == null) {
      throw new NullPointerException("interceptor " + interceptor + " returned null");
    }

    return interceptedResponse;
  }

  private boolean sameConnection(HttpUrl url) {
    return url.host().equals(connection().route().address().url().host())
        && url.port() == connection().route().address().url().port();
  }
}
