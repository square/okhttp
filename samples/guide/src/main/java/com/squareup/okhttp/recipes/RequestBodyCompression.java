/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.recipes;

import com.google.gson.Gson;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

public final class RequestBodyCompression {
  /**
   * The Google API KEY for OkHttp recipes. If you're using Google APIs for anything other than
   * running these examples, please request your own client ID!
   *   https://console.developers.google.com/project
   */
  public static final String GOOGLE_API_KEY = "AIzaSyAx2WZYe0My0i-uGurpvraYJxO7XNbwiGs";
  public static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json");

  private final OkHttpClient client = new OkHttpClient();

  public RequestBodyCompression() {
    client.interceptors().add(new GzipRequestInterceptor());
  }

  public void run() throws Exception {
    Map<String, String> requestBody = new LinkedHashMap<>();
    requestBody.put("longUrl", "https://publicobject.com/2014/12/04/html-formatting-javadocs/");
    RequestBody jsonRequestBody = RequestBody.create(
        MEDIA_TYPE_JSON, new Gson().toJson(requestBody));
    Request request = new Request.Builder()
        .url("https://www.googleapis.com/urlshortener/v1/url?key=" + GOOGLE_API_KEY)
        .post(jsonRequestBody)
        .build();

    Response response = client.newCall(request).execute();
    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

    System.out.println(response.body().string());
  }

  public static void main(String... args) throws Exception {
    new RequestBodyCompression().run();
  }

  /** This interceptor compresses the HTTP request body. Many webservers can't handle this! */
  static class GzipRequestInterceptor implements Interceptor {
    @Override public Response intercept(Chain chain) throws IOException {
      Request originalRequest = chain.request();
      if (originalRequest.body() == null || originalRequest.header("Content-Encoding") != null) {
        return chain.proceed(originalRequest);
      }

      Request compressedRequest = originalRequest.newBuilder()
          .header("Content-Encoding", "gzip")
          .method(originalRequest.method(), gzip(originalRequest.body()))
          .build();
      return chain.proceed(compressedRequest);
    }

    private RequestBody gzip(final RequestBody body) {
      return new RequestBody() {
        @Override public MediaType contentType() {
          return body.contentType();
        }

        @Override public long contentLength() {
          return -1; // We don't know the compressed length in advance!
        }

        @Override public void writeTo(BufferedSink sink) throws IOException {
          BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));
          body.writeTo(gzipSink);
          gzipSink.close();
        }
      };
    }
  }
}
