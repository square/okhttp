/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.recipes;

import java.io.IOException;
import java.util.Date;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class CurrentDateHeader {
  private final OkHttpClient client = new OkHttpClient.Builder()
      .addInterceptor(new CurrentDateInterceptor())
      .build();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://publicobject.com/helloworld.txt")
        .build();

    try (Response response = client.newCall(request).execute()) {
      System.out.println(response.request().header("Date"));
    }
  }

  static class CurrentDateInterceptor implements Interceptor {
    @Override public Response intercept(Chain chain) throws IOException {
      Request request = chain.request();
      Headers newHeaders = request.headers()
          .newBuilder()
          .add("Date", new Date())
          .build();
      Request newRequest = request.newBuilder()
          .headers(newHeaders)
          .build();
      return chain.proceed(newRequest);
    }
  }

  public static void main(String... args) throws Exception {
    new CurrentDateHeader().run();
  }
}
