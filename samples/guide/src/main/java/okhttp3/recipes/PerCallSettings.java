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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class PerCallSettings {
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://httpbin.org/delay/1") // This URL is served with a 1 second delay.
        .build();

    try {
      OkHttpClient cloned = client.clone(); // Clone to make a customized OkHttp for this request.
      cloned.setReadTimeout(500, TimeUnit.MILLISECONDS);

      Response response = cloned.newCall(request).execute();
      System.out.println("Response 1 succeeded: " + response);
    } catch (IOException e) {
      System.out.println("Response 1 failed: " + e);
    }

    try {
      OkHttpClient cloned = client.clone(); // Clone to make a customized OkHttp for this request.
      cloned.setReadTimeout(3000, TimeUnit.MILLISECONDS);

      Response response = cloned.newCall(request).execute();
      System.out.println("Response 2 succeeded: " + response);
    } catch (IOException e) {
      System.out.println("Response 2 failed: " + e);
    }
  }

  public static void main(String... args) throws Exception {
    new PerCallSettings().run();
  }
}
