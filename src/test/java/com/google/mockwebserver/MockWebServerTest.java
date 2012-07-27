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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import junit.framework.TestCase;

public final class MockWebServerTest extends TestCase {

    private MockWebServer server = new MockWebServer();

    @Override protected void setUp() throws Exception {
        super.setUp();
    }

    @Override protected void tearDown() throws Exception {
        server.shutdown();
        super.tearDown();
    }

    public void testRegularResponse() throws Exception {
        server.enqueue(new MockResponse().setBody("hello world"));
        server.play();

        URL url = server.getUrl("/");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Accept-Language", "en-US");
        InputStream in = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
        assertEquals("hello world", reader.readLine());

        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());
        assertTrue(request.getHeaders().contains("Accept-Language: en-US"));
    }

    public void testRedirect() throws Exception {
        server.play();
        server.enqueue(new MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: " + server.getUrl("/new-path"))
                .setBody("This page has moved!"));
        server.enqueue(new MockResponse().setBody("This is the new location!"));

        URLConnection connection = server.getUrl("/").openConnection();
        InputStream in = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        assertEquals("This is the new location!", reader.readLine());

        RecordedRequest first = server.takeRequest();
        assertEquals("GET / HTTP/1.1", first.getRequestLine());
        RecordedRequest redirect = server.takeRequest();
        assertEquals("GET /new-path HTTP/1.1", redirect.getRequestLine());
    }

    /**
     * Test that MockWebServer blocks for a call to enqueue() if a request
     * is made before a mock response is ready.
     */
    public void testDispatchBlocksWaitingForEnqueue() throws Exception {
        server.play();

        new Thread() {
            @Override public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                server.enqueue(new MockResponse().setBody("enqueued in the background"));
            }
        }.start();

        URLConnection connection = server.getUrl("/").openConnection();
        InputStream in = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        assertEquals("enqueued in the background", reader.readLine());
    }

    public void testNonHexadecimalChunkSize() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("G\r\nxxxxxxxxxxxxxxxx\r\n0\r\n\r\n")
                .clearHeaders()
                .addHeader("Transfer-encoding: chunked"));
        server.play();

        URLConnection connection = server.getUrl("/").openConnection();
        InputStream in = connection.getInputStream();
        try {
            in.read();
            fail();
        } catch (IOException expected) {
        }
    }

    public void testResponseTimeout() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("ABC")
                .clearHeaders()
                .addHeader("Content-Length: 4"));
        server.enqueue(new MockResponse()
                .setBody("DEF"));
        server.play();

        URLConnection urlConnection = server.getUrl("/").openConnection();
        urlConnection.setReadTimeout(1000);
        InputStream in = urlConnection.getInputStream();
        assertEquals('A', in.read());
        assertEquals('B', in.read());
        assertEquals('C', in.read());
        try {
            in.read(); // if Content-Length was accurate, this would return -1 immediately
            fail();
        } catch (SocketTimeoutException expected) {
        }

        URLConnection urlConnection2 = server.getUrl("/").openConnection();
        InputStream in2 = urlConnection2.getInputStream();
        assertEquals('D', in2.read());
        assertEquals('E', in2.read());
        assertEquals('F', in2.read());
        assertEquals(-1, in2.read());

        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertEquals(0, server.takeRequest().getSequenceNumber());
    }
}
