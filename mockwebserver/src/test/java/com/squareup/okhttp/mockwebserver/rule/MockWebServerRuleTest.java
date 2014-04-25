/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.mockwebserver.rule;

import com.squareup.okhttp.mockwebserver.MockResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MockWebServerRuleTest {

  private MockWebServerRule server = new MockWebServerRule();

  @After public void tearDown() {
    server.after();
  }

  @Test public void whenRuleCreatedPortIsAvailableAndServerNotYetPlayed() throws IOException {
    assertTrue(server.getPort() > 0);

    try {
      server.get().getPort();
      fail();
    } catch (IllegalStateException e) {

    }

    // Verify the port is available.
    new ServerSocket(server.getPort()).close();
  }

  @Test public void differentRulesGetDifferentPorts() throws IOException {
    assertNotEquals(server.getPort(), new MockWebServerRule().getPort());
  }

  @Test public void beforePlaysServer() throws Exception {
    server.before();
    assertEquals(server.getPort(), server.get().getPort());
    server.getUrl("/").openConnection().connect();
  }

  @Test public void afterStopsServer() throws Exception {
    server.before();
    server.after();

    try {
      server.getUrl("/").openConnection().connect();
      fail();
    } catch (ConnectException e) {
    }
  }

  @Test public void typicalUsage() throws Exception {
    server.before(); // Implicitly called when @Rule.

    server.enqueue(new MockResponse().setBody("hello world"));

    URL url = server.getUrl("/aaa");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    InputStream in = connection.getInputStream();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    assertEquals("hello world", reader.readLine());

    assertEquals(1, server.getRequestCount());
    assertEquals("GET /aaa HTTP/1.1", server.takeRequest().getRequestLine());

    server.after(); // Implicitly called when @Rule.
  }
}

