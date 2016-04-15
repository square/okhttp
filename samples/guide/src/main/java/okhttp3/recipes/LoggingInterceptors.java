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
package okhttp3.recipes;

import java.io.IOException;
import java.util.logging.Logger;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class LoggingInterceptors {
  private static final Logger logger = Logger.getLogger(LoggingInterceptors.class.getName());
  private final OkHttpClient client = new OkHttpClient.Builder()
      .addInterceptor(new LoggingInterceptor())
      .build();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://publicobject.com/helloworld.txt")
        .build();

    Response response = client.newCall(request).execute();
    response.body().close();
  }

  private static class LoggingInterceptor implements Interceptor {
    @Override public Response intercept(Chain chain) throws IOException {
      long t1 = System.nanoTime();
      Request request = chain.request();
      logger.info(String.format("Sending request %s on %s%n%s",
          request.url(), chain.connection(), request.headers()));
      Response response = chain.proceed(request);

      long t2 = System.nanoTime();
      logger.info(String.format("Received response for %s in %.1fms%n%s",
          request.url(), (t2 - t1) / 1e6d, response.headers()));
      return response;
    }
  }

  public static void main(String... args) throws Exception {
    new LoggingInterceptors().run();
  }
}
