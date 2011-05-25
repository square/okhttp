/*
 * Copyright (C) 2011 Google Inc.
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

package com.google.mockwebserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import junit.framework.TestCase;

public final class MockWebServerTest extends TestCase {
    public void testRegularResponse() throws Exception {
        // 1. Enqueue a response.
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("hello world"));
        server.play();

        // 2. Make a request that retrieves that response.
        URL url = server.getUrl("/");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Accept-Language", "en-US");
        InputStream in = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
        assertEquals("hello world", reader.readLine());

        // 3. Validate the request.
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());
        assertTrue(request.getHeaders().contains("Accept-Language: en-US"));
    }
}
