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

package okhttp3.modules.test;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.modules.OkHttpCaller;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaModuleTest {
  @Test
  public void testVisibility() {
    // Just check we can run code that depends on OkHttp types
    OkHttpCaller.callOkHttp(HttpUrl.get("https://square.com/robots.txt"));
  }

  @Test
  public void testMockWebServer() throws IOException {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse(200, Headers.of(), "Hello, Java9!"));
    server.start();

    // Just check we can run code that depends on OkHttp types
    Call call = OkHttpCaller.callOkHttp(server.url("/"));

    try (Response response = call.execute();) {
      System.out.println(response.body().string());
    }
  }

  @Test
  public void testModules() {
    Module okHttpModule = OkHttpClient.class.getModule();
    assertEquals("okhttp3", okHttpModule.getName());
    assertTrue(okHttpModule.getPackages().contains("okhttp3"));

    Module loggingInterceptorModule = HttpLoggingInterceptor.class.getModule();
    assertEquals("okhttp3.logging", loggingInterceptorModule.getName());
    assertTrue(loggingInterceptorModule.getPackages().contains("okhttp3.logging"));
  }
}
