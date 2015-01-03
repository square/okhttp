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
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.rules.ExternalResource;

/** @deprecated {@link MockWebServer} can now be used directly as a rule. */
public class MockWebServerRule extends ExternalResource {
  private static final Logger logger = Logger.getLogger(MockWebServerRule.class.getName());

  private final MockWebServer server = new MockWebServer();
  private boolean started;

  @Override protected void before() {
    if (started) return;
    started = true;
    try {
      server.play();
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
    if (!started) before();
    return server.getPort();
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
}
