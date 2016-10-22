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

/** Authorization for an application to make Slack API calls on behalf of a user. */
@SuppressWarnings("checkstyle:membername")
public final class OAuthSession {
  public final boolean ok;
  public final String access_token;
  public final String scope;
  public final String user_id;
  public final String team_name;
  public final String team_id;

  public OAuthSession(
      boolean ok, String accessToken, String scope, String userId, String teamName, String teamId) {
    this.ok = ok;
    this.access_token = accessToken;
    this.scope = scope;
    this.user_id = userId;
    this.team_name = teamName;
    this.team_id = teamId;
  }

  @Override public String toString() {
    return String.format("(ok=%s, access_token=%s, scope=%s, user_id=%s, team_name=%s, team_id=%s)",
        ok, access_token, scope, user_id, team_name, team_id);
  }
}
