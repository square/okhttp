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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.rules.ExternalResource;

/**
 * Allows you to use {@link MockWebServer} as a JUnit test rule.
 *
 * <p>This rule starts {@link MockWebServer} on an available port before your test runs, and shuts
 * it down after it completes.
 */
public class MockWebServerRule extends ExternalResource {
  private static final Logger logger = Logger.getLogger(MockWebServerRule.class.getName());

  private final int port = pickPort();
  private final MockWebServer server = new MockWebServer();

  @Override protected void before() {
    try {
      server.play(port);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override protected void after() {
    try {
      server.shutdown();
    } catch (IOException e) {
      logger.log(Level.WARNING, "MockWebServer shutdown failed", e);
    }
  }

  public int getPort() {
    return port;
  }

  public int getRequestCount() {
    return server.getRequestCount();
  }

  public void enqueue(MockResponse response) {
    server.enqueue(response);
  }

  public RecordedRequest takeRequest() throws InterruptedException {
    return server.takeRequest();
  }

  public URL getUrl(String path) {
    return server.getUrl(path);
  }

  /** For any other functionality, use the {@linkplain MockWebServer} directly. */
  public MockWebServer get() {
    return server;
  }

  private static int pickPort() {
    ServerSocket socket = null;
    try {
      socket = new ServerSocket(0);
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      Util.closeQuietly(socket);
    }
  }
}
