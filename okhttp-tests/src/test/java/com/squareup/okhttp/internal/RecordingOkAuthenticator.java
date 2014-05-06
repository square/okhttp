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
package com.squareup.okhttp.internal;

import com.squareup.okhttp.OkAuthenticator;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class RecordingOkAuthenticator implements OkAuthenticator {
  public final List<Response> responses = new ArrayList<Response>();
  public final List<Proxy> proxies = new ArrayList<Proxy>();
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

  @Override public Request authenticate(Proxy proxy, Response response) {
    responses.add(response);
    proxies.add(proxy);
    return response.request().newBuilder()
        .addHeader("Authorization", credential)
        .build();
  }

  @Override public Request authenticateProxy(Proxy proxy, Response response) {
    responses.add(response);
    proxies.add(proxy);
    return response.request().newBuilder()
        .addHeader("Proxy-Authorization", credential)
        .build();
  }
}
