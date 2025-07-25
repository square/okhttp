/*
 * Copyright (C) 2025 Block, Inc.
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

package com.squareup.okhttp3.maventest;

import java.io.IOException;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SampleHttpClient {
  private final OkHttpClient client;

  public SampleHttpClient() {
    client = new OkHttpClient.Builder().build();
  }

  public void makeCall(HttpUrl url) throws IOException {
    try (Response response = client.newCall(new Request(url, Headers.EMPTY, "GET", null)).execute()) {
      System.out.println(response.body().string());
    }
  }
}
