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

import java.io.File;
import java.io.IOException;
import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class RewriteResponseCacheControl {
  /** Dangerous interceptor that rewrites the server's cache-control header. */
  private static final Interceptor REWRITE_CACHE_CONTROL_INTERCEPTOR = chain -> {
    Response originalResponse = chain.proceed(chain.request());
    return originalResponse.newBuilder()
        .header("Cache-Control", "max-age=60")
        .build();
  };

  private final OkHttpClient client;

  public RewriteResponseCacheControl(File cacheDirectory) throws Exception {
    Cache cache = new Cache(cacheDirectory, 1024 * 1024);
    cache.evictAll();

    client = new OkHttpClient.Builder()
        .cache(cache)
        .build();
  }

  public void run() throws Exception {
    for (int i = 0; i < 5; i++) {
      System.out.println("    Request: " + i);

      Request request = new Request.Builder()
          .url("https://api.github.com/search/repositories?q=http")
          .build();

      OkHttpClient clientForCall;
      if (i == 2) {
        // Force this request's response to be written to the cache. This way, subsequent responses
        // can be read from the cache.
        System.out.println("Force cache: true");
        clientForCall = client.newBuilder()
            .addNetworkInterceptor(REWRITE_CACHE_CONTROL_INTERCEPTOR)
            .build();
      } else {
        System.out.println("Force cache: false");
        clientForCall = client;
      }

      try (Response response = clientForCall.newCall(request).execute()) {
        if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

        System.out.println("    Network: " + (response.networkResponse() != null));
        System.out.println();
      }
    }
  }

  public static void main(String... args) throws Exception {
    new RewriteResponseCacheControl(new File("RewriteResponseCacheControl.tmp")).run();
  }
}
