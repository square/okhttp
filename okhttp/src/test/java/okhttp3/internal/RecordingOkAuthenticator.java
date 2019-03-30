/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Authenticator;
import okhttp3.Challenge;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public final class RecordingOkAuthenticator implements Authenticator {
  public final List<Response> responses = new ArrayList<>();
  public final List<Route> routes = new ArrayList<>();
  public @Nullable String credential;
  public @Nullable String scheme;

  public RecordingOkAuthenticator(@Nullable String credential, @Nullable String scheme) {
    this.credential = credential;
    this.scheme = scheme;
  }

  public Response onlyResponse() {
    if (responses.size() != 1) throw new IllegalStateException();
    return responses.get(0);
  }

  public Route onlyRoute() {
    if (routes.size() != 1) throw new IllegalStateException();
    return routes.get(0);
  }

  @Override public Request authenticate(Route route, Response response) throws IOException {
    if (route == null) throw new NullPointerException("route == null");
    if (response == null) throw new NullPointerException("response == null");

    responses.add(response);
    routes.add(route);

    if (!schemeMatches(response) || credential == null) return null;

    String header = response.code() == 407 ? "Proxy-Authorization" : "Authorization";
    return response.request().newBuilder()
        .addHeader(header, credential)
        .build();
  }

  private boolean schemeMatches(Response response) {
    if (scheme == null) return true;

    for (Challenge challenge : response.challenges()) {
      if (challenge.scheme().equalsIgnoreCase(scheme)) return true;
    }

    return false;
  }
}
