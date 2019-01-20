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

import java.io.IOException;
import java.io.InterruptedIOException;
import okhttp3.HttpUrl;
import okio.Timeout;

/** A connection to Slack as a single user. */
public final class SlackClient {
  private final SlackApi slackApi;
  private OAuthSessionFactory sessionFactory;

  /** Guarded by this. */
  private OAuthSession session;

  public SlackClient(SlackApi slackApi) {
    this.slackApi = slackApi;
  }

  /** Shows a browser URL to authorize this app to act as this user. */
  public void requestOauthSession(String scopes, String team) throws Exception {
    if (sessionFactory == null) {
      sessionFactory = new OAuthSessionFactory(slackApi);
      sessionFactory.start();
    }

    HttpUrl authorizeUrl = sessionFactory.newAuthorizeUrl(scopes, team, session -> {
      initOauthSession(session);
      System.out.printf("session granted: %s\n", session);
    });

    System.out.printf("open this URL in a browser: %s\n", authorizeUrl);
  }

  /** Set the OAuth session for this client. */
  public synchronized void initOauthSession(OAuthSession session) {
    this.session = session;
    this.notifyAll();
  }

  /** Waits for an OAuth session for this client to be set. */
  public synchronized void awaitAccessToken(Timeout timeout) throws InterruptedIOException {
    while (session == null) {
      timeout.waitUntilNotified(this);
    }
  }

  /** Starts a real time messaging session. */
  public void startRtm() throws IOException {
    String accessToken;
    synchronized (this) {
      accessToken = session.access_token;
    }

    RtmSession rtmSession = new RtmSession(slackApi);
    rtmSession.open(accessToken);
  }

  public static void main(String... args) throws Exception {
    String clientId = "0000000000.00000000000";
    String clientSecret = "00000000000000000000000000000000";
    int port = 53203;
    SlackApi slackApi = new SlackApi(clientId, clientSecret, port);

    SlackClient client = new SlackClient(slackApi);
    String scopes = "channels:history channels:read channels:write chat:write:bot chat:write:user "
        + "dnd:read dnd:write emoji:read files:read files:write:user groups:history groups:read "
        + "groups:write im:history im:read im:write mpim:history mpim:read mpim:write pins:read "
        + "pins:write reactions:read reactions:write search:read stars:read stars:write team:read "
        + "usergroups:read usergroups:write users:read users:write identify";

    if (true) {
      client.requestOauthSession(scopes, null);
    } else {
      OAuthSession session = new OAuthSession(true,
          "xoxp-XXXXXXXXXX-XXXXXXXXXX-XXXXXXXXXXX-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
          scopes, "UXXXXXXXX", "My Slack Group", "TXXXXXXXX");
      client.initOauthSession(session);
    }

    client.awaitAccessToken(Timeout.NONE);
    client.startRtm();
  }
}
