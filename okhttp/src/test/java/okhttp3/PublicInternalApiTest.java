/*
 * Copyright (C) 2019 Square, Inc.
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

import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("ALL") public class PublicInternalApiTest {
  @Test public void permitsRequestBody() {
    assertTrue(HttpMethod.permitsRequestBody("POST"));
    assertFalse(HttpMethod.permitsRequestBody("GET"));
  }

  @Test public void requiresRequestBody() {
    assertTrue(HttpMethod.requiresRequestBody("PUT"));
    assertFalse(HttpMethod.requiresRequestBody("GET"));
  }

  @Test public void hasBody() {
    Request request = new Request.Builder().url("http://example.com").build();
    Response response = new Response.Builder().code(200)
        .message("OK")
        .request(request)
        .protocol(Protocol.HTTP_2)
        .build();
    assertTrue(HttpHeaders.hasBody(response));
  }
}
