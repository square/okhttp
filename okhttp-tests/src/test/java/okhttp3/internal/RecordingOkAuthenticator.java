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
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public final class RecordingOkAuthenticator implements Authenticator {
  public final List<Response> responses = new ArrayList<>();
  public final List<Proxy> proxies = new ArrayList<>();
  public final String credential;

  public RecordingOkAuthenticator(String credential) {
    this.credential = credential;
  }

  public Response onlyResponse() {
    if (responses.size() != 1) throw new IllegalStateException();
    return responses.get(0);
  }

  public Proxy onlyProxy() {
    if (proxies.size() != 1) throw new IllegalStateException();
    return proxies.get(0);
  }

  @Override public Request authenticate(Route route, Response response) throws IOException {
    responses.add(response);
    proxies.add(route.proxy());
    String header = response.code() == 407 ? "Proxy-Authorization" : "Authorization";
    return response.request().newBuilder()
        .addHeader(header, credential)
        .build();
  }

  @Override
  public Request authenticatePreemptive(Route route, Request request) throws IOException {
	// TODO Auto-generated method stub
	return null;
  }

  @Override
  public boolean isPreemptive() {
	// TODO Auto-generated method stub
	return false;
  }
}
