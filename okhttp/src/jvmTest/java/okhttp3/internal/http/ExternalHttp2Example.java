/*
 * Copyright (C) 2009 The Android Open Source Project
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

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import static okhttp3.internal.Util.immutableListOf;

public final class ExternalHttp2Example {
  public static void main(String[] args) throws Exception {
    OkHttpClient client = new OkHttpClient.Builder()
        .protocols(immutableListOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("https://www.google.ca/")
        .build());

    Response response = call.execute();
    try {
      System.out.println(response.code());
      System.out.println("PROTOCOL " + response.protocol());

      String line;
      while ((line = response.body().source().readUtf8Line()) != null) {
        System.out.println(line);
      }
    } finally {
      response.body().close();
    }

    client.connectionPool().evictAll();
  }
}
