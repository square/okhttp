/*
 * Copyright (C) 2009 The Android Open Source Project
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
package okhttp3;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FakeProxySelector extends ProxySelector {
  public final List<Proxy> proxies = new ArrayList<>();

  public FakeProxySelector addProxy(Proxy proxy) {
    proxies.add(proxy);
    return this;
  }

  @Override public List<Proxy> select(URI uri) {
    // Don't handle 'socket' schemes, which the RI's Socket class may request (for SOCKS).
    return uri.getScheme().equals("http") || uri.getScheme().equals("https") ? proxies
        : Collections.singletonList(Proxy.NO_PROXY);
  }

  @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
  }
}
