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

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;

public class NewApiSample {
  private static void asyncCall() {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder()
        .url("http://squareup.com")
        .header("User-Agent", "OkHttp 2.0-SNAPSHOT")
        .build();
    client.enqueue(request, new Response.Receiver() {
      @Override public void receive(Response response) throws IOException {
        String contentType = response.header("Content-Type", "text/plain");
        if (response.code() == 200 && contentType.startsWith("application/json")) {
          System.out.println("SUCCESS: " + response.body().string());
        } else {
          System.out.println("FAIL!" + response.code());
        }
      }
    });
  }
}
