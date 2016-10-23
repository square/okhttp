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

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocketCall;
import okio.ByteString;

/**
 * API access to the <a href="https://api.slack.com/apps">Slack API</a> as an application. One
 * instance of this class may operate without a user, or on behalf of many users. Use the Slack API
 * dashboard to create a client ID and secret for this application.
 *
 * <p>You must configure your Slack API OAuth & Permissions page with a localhost URL like {@code
 * http://localhost:53203/oauth/}, passing the same port to this class’ constructor.
 */
public final class SlackApi {
  private final HttpUrl baseUrl = HttpUrl.parse("https://slack.com/api/");
  private final OkHttpClient httpClient;
  private final Moshi moshi;

  public final String clientId;
  public final String clientSecret;
  public final int port;

  public SlackApi(String clientId, String clientSecret, int port) {
    this.httpClient = new OkHttpClient.Builder()
        .build();
    this.moshi = new Moshi.Builder()
        .add(new SlackJsonAdapters())
        .build();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.port = port;
  }

  /** See https://api.slack.com/docs/oauth. */
  public HttpUrl authorizeUrl(String scopes, HttpUrl redirectUrl, ByteString state, String team) {
    HttpUrl.Builder builder = baseUrl.newBuilder("/oauth/authorize")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("scope", scopes)
        .addQueryParameter("redirect_uri", redirectUrl.toString())
        .addQueryParameter("state", state.base64());

    if (team != null) {
      builder.addQueryParameter("team", team);
    }

    return builder.build();
  }

  /** See https://api.slack.com/methods/oauth.access. */
  public OAuthSession exchangeCode(String code, HttpUrl redirectUrl) throws IOException {
    HttpUrl url = baseUrl.newBuilder("oauth.access")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("client_secret", clientSecret)
        .addQueryParameter("code", code)
        .addQueryParameter("redirect_uri", redirectUrl.toString())
        .build();
    Request request = new Request.Builder()
        .url(url)
        .build();
    Call call = httpClient.newCall(request);
    try (Response response = call.execute()) {
      JsonAdapter<OAuthSession> jsonAdapter = moshi.adapter(OAuthSession.class);
      return jsonAdapter.fromJson(response.body().source());
    }
  }

  /** See https://api.slack.com/methods/rtm.start. */
  public RtmStartResponse rtmStart(String accessToken) throws IOException {
    HttpUrl url = baseUrl.newBuilder("rtm.start")
        .addQueryParameter("token", accessToken)
        .build();
    Request request = new Request.Builder()
        .url(url)
        .build();
    Call call = httpClient.newCall(request);
    try (Response response = call.execute()) {
      JsonAdapter<RtmStartResponse> jsonAdapter = moshi.adapter(RtmStartResponse.class);
      return jsonAdapter.fromJson(response.body().source());
    }
  }

  /** See https://api.slack.com/rtm. */
  public WebSocketCall rtm(HttpUrl url) {
    return httpClient.newWebSocketCall(new Request.Builder()
        .url(url)
        .build());
  }

  static final class SlackJsonAdapters {
    @ToJson String urlToJson(HttpUrl httpUrl) {
      return httpUrl.toString();
    }

    @FromJson HttpUrl urlFromJson(String urlString) {
      if (urlString.startsWith("wss:")) urlString = "https:" + urlString.substring(4);
      if (urlString.startsWith("ws:")) urlString = "http:" + urlString.substring(3);
      return HttpUrl.parse(urlString);
    }
  }
}
