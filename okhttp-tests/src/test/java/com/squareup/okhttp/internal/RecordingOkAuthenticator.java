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
  public final List<String> calls = new ArrayList<String>();
  public final Credential credential;

  public RecordingOkAuthenticator(Credential credential) {
    this.credential = credential;
  }

  @Override public Credential authenticate(Proxy proxy, URL url, List<Challenge> challenges)
      throws IOException {
    calls.add("authenticate"
        + " proxy=" + proxy.type()
        + " url=" + url
        + " challenges=" + challenges);
    return credential;
  }

  @Override public Credential authenticateProxy(Proxy proxy, URL url, List<Challenge> challenges)
      throws IOException {
    calls.add("authenticateProxy"
        + " proxy=" + proxy.type()
        + " url=" + url
        + " challenges=" + challenges);
    return credential;
  }
}
