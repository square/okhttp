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
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class RecordingOkAuthenticator implements OkAuthenticator {
  public final List<URL> urls = new ArrayList<URL>();
  public final List<List<Challenge>> challengesList = new ArrayList<List<Challenge>>();
  public final List<Proxy> proxies = new ArrayList<Proxy>();
  public final Credential credential;

  public RecordingOkAuthenticator(Credential credential) {
    this.credential = credential;
  }

  public URL onlyUrl() {
    if (urls.size() != 1) throw new IllegalStateException();
    return urls.get(0);
  }

  public List<Challenge> onlyChallenge() {
    if (challengesList.size() != 1) throw new IllegalStateException();
    return challengesList.get(0);
  }

  public Proxy onlyProxy() {
    if (proxies.size() != 1) throw new IllegalStateException();
    return proxies.get(0);
  }

  @Override public Credential authenticate(Proxy proxy, URL url, List<Challenge> challenges)
      throws IOException {
    urls.add(url);
    challengesList.add(challenges);
    proxies.add(proxy);
    return credential;
  }

  @Override public Credential authenticateProxy(Proxy proxy, URL url, List<Challenge> challenges)
      throws IOException {
    urls.add(url);
    challengesList.add(challenges);
    proxies.add(proxy);
    return credential;
  }
}
