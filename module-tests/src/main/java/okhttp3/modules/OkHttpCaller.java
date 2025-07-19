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
import okhttp3.OkHttpClient;
import okhttp3.Request;
//import okhttp3.logging.LoggingEventListener;

/**
 * Just checking compilation works
 */
public class OkHttpCaller {
  public static Call callOkHttp() {
    OkHttpClient client = new OkHttpClient
      .Builder()
      .eventListenerFactory(LoggingEventListener.Factory())
      .build();
    return client.newCall(new Request.Builder().url("https://square.com/robots.txt").build());
  }
}
