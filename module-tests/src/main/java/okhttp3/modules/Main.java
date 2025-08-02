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

package okhttp3.modules;

import okhttp3.Call;
import okhttp3.HttpUrl;

import java.io.IOException;

public class Main {
  public static void main(String[] args) throws IOException {
    Call call = OkHttpCaller.callOkHttp(HttpUrl.get("https://square.com/robots.txt"));

    System.out.println(call.execute().body().string());
  }
}
