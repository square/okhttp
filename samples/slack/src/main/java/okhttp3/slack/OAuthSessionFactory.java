/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.slack;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/**
 * Runs a MockWebServer on localhost and uses it as the backend to receive an OAuth session.
 *
 * <p>Clients should call {@link #start}, {@link #newAuthorizeUrl} and {@link #close} in that order.
 * Clients may request multiple sessions.
 */
public final class OAuthSessionFactory extends Dispatcher implements Closeable {
  private final SecureRandom secureRandom = new SecureRandom();

  private final SlackApi slackApi;
  private MockWebServer mockWebServer;

  /** Guarded by this. */
  private Map<ByteString, Listener> listeners = new LinkedHashMap<>();

  public OAuthSessionFactory(SlackApi slackApi) {
    this.slackApi = slackApi;
  }

  public void start() throws Exception {
    if (mockWebServer != null) throw new IllegalStateException();

    mockWebServer = new MockWebServer();
    mockWebServer.setDispatcher(this);
    mockWebServer.start(slackApi.port);
  }

  public HttpUrl newAuthorizeUrl(String scopes, String team, Listener listener) {
    if (mockWebServer == null) throw new IllegalStateException();

    ByteString state = randomToken();
    synchronized (this) {
      listeners.put(state, listener);
    }

    return slackApi.authorizeUrl(scopes, redirectUrl(), state, team);
  }

  private ByteString randomToken() {
    byte[] bytes = new byte[16];
    secureRandom.nextBytes(bytes);
    return ByteString.of(bytes);
  }

  private HttpUrl redirectUrl() {
    return mockWebServer.url("/oauth/");
  }

  /** When the browser hits the redirect URL, use the provided code to ask Slack for a session. */
  @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
    HttpUrl requestUrl = mockWebServer.url(request.getPath());
    String code = requestUrl.queryParameter("code");
    String stateString = requestUrl.queryParameter("state");
    ByteString state = stateString != null ? ByteString.decodeBase64(stateString) : null;

    Listener listener;
    synchronized (this) {
      listener = listeners.get(state);
    }

    if (code == null || listener == null) {
      return new MockResponse()
          .setResponseCode(404)
          .setBody("unexpected request");
    }

    try {
      OAuthSession session = slackApi.exchangeCode(code, redirectUrl());
      listener.sessionGranted(session);
    } catch (IOException e) {
      return new MockResponse()
          .setResponseCode(400)
          .setBody("code exchange failed: " + e.getMessage());
    }

    synchronized (this) {
      listeners.remove(state);
    }

    // Success!
    return new MockResponse()
        .setResponseCode(302)
        .addHeader("Location", "https://twitter.com/CuteEmergency/status/789457462864863232");
  }

  public interface Listener {
    void sessionGranted(OAuthSession session);
  }

  @Override public void close() {
    if (mockWebServer == null) throw new IllegalStateException();
    try {
      mockWebServer.close();
    } catch (IOException ignored) {
    }
  }
}
