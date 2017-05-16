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
import java.util.Arrays;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public final class SynchronousHttp2Get {
  // setting protocols not required, but avoids SPDY
  private final OkHttpClient client =
      new OkHttpClient.Builder().protocols(Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2))
          .build();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://http2.golang.org/reqinfo")
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println("Protocol: " + response.protocol());

      System.out.println("Body:\n-----");
      System.out.println(response.body().string());
    }

    // Shutdown http/2 connection listener.
    //
    // Required for http/2 connections with correctly configured clients e.g. JDK8 with alpn-boot
    // n.b. only call when you are completely finished with this and all derived clients
    // as the connectionPool is shared with other instances created via client.newBuilder().build()
    client.connectionPool().evictAll();
  }

  public static void main(String... args) throws Exception {
    new SynchronousHttp2Get().run();
  }
}
