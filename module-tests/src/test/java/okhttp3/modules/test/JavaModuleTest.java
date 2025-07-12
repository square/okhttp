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

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.modules.OkHttpCaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaModuleTest {
  @Test
  public void testVisibility() {
    // Just check we can run code that depends on OkHttp types
    OkHttpCaller.callOkHttp();
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
