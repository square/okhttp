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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Failure;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;
import java.net.HttpURLConnection;

public final class Job implements Runnable {
  final HttpURLConnection connection;
  final Request request;
  final Response.Receiver responseReceiver;
  final Dispatcher dispatcher;

  public Job(Dispatcher dispatcher, HttpURLConnection connection, Request request,
      Response.Receiver responseReceiver) {
    this.dispatcher = dispatcher;
    this.connection = connection;
    this.request = request;
    this.responseReceiver = responseReceiver;
  }

  @Override public void run() {
    try {
      sendRequest();
      Response response = readResponse();
      responseReceiver.onResponse(response);
    } catch (IOException e) {
      responseReceiver.onFailure(new Failure.Builder()
          .request(request)
          .exception(e)
          .build());
    } finally {
      connection.disconnect();
      dispatcher.finished(this);
    }
  }

  private HttpURLConnection sendRequest() throws IOException {
    for (int i = 0; i < request.headerCount(); i++) {
      connection.addRequestProperty(request.headerName(i), request.headerValue(i));
    }
    Request.Body body = request.body();
    if (body != null) {
      connection.setDoOutput(true);
      long contentLength = body.contentLength();
      if (contentLength == -1 || contentLength > Integer.MAX_VALUE) {
        connection.setChunkedStreamingMode(0);
      } else {
        // Don't call setFixedLengthStreamingMode(long); that's only available on Java 1.7+.
        connection.setFixedLengthStreamingMode((int) contentLength);
      }
      body.writeTo(connection.getOutputStream());
    }
    return connection;
  }

  private Response readResponse() throws IOException {
    int responseCode = connection.getResponseCode();
    Response.Builder responseBuilder = new Response.Builder(request, responseCode);

    for (int i = 0; true; i++) {
      String name = connection.getHeaderFieldKey(i);
      if (name == null) break;
      String value = connection.getHeaderField(i);
      responseBuilder.addHeader(name, value);
    }

    responseBuilder.body(new Dispatcher.RealResponseBody(connection, connection.getInputStream()));
    // TODO: set redirectedBy
    return responseBuilder.build();
  }
}
