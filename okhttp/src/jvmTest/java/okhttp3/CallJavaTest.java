/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CallJavaTest {
  @RegisterExtension
  OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client = clientTestRule.newClient();

  @Test
  public void tagsSeededFromRequest() {
    Request request = new Request.Builder()
      .url(HttpUrl.get("https://square.com/"))
      .tag(Integer.class, 5)
      .tag(String.class, "hello")
      .build();
    Call call = client.newCall(request);

    assertEquals(5, call.tag(Integer.class));
    assertEquals("hello", call.tag(String.class));
    assertEquals(null, call.tag(Boolean.class));
    assertEquals(null, call.tag(Object.class));
  }

  @Test
  public void tagsCanBeComputed() {
    Request request = new Request.Builder()
      .url(HttpUrl.get("https://square.com/"))
      .build();
    Call call = client.newCall(request);

    assertEquals("a", call.tag(String.class, () -> "a"));
    assertEquals("a", call.tag(String.class, () -> "b"));
  }
}
