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

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.util.concurrent.TimeUnit;

public final class ConfigureTimeouts {
  private final OkHttpClient client;

  public ConfigureTimeouts() throws Exception {
    client = new OkHttpClient();
    client.setConnectTimeout(10, TimeUnit.SECONDS);
    client.setWriteTimeout(10, TimeUnit.SECONDS);
    client.setReadTimeout(30, TimeUnit.SECONDS);
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
        .build();

    Response response = client.newCall(request).execute();
    System.out.println("Response completed: " + response);
  }

  public static void main(String... args) throws Exception {
    new ConfigureTimeouts().run();
  }
}
