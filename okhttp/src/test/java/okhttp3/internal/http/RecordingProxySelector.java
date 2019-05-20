/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.Util;

import static org.assertj.core.api.Assertions.assertThat;

public final class RecordingProxySelector extends ProxySelector {
  public final List<Proxy> proxies = new ArrayList<>();
  public final List<URI> requestedUris = new ArrayList<>();
  public final List<String> failures = new ArrayList<>();

  @Override public List<Proxy> select(URI uri) {
    requestedUris.add(uri);
    return proxies;
  }

  public void assertRequests(URI... expectedUris) {
    assertThat(requestedUris).containsExactly(expectedUris);
    requestedUris.clear();
  }

  @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
    InetSocketAddress socketAddress = (InetSocketAddress) sa;
    failures.add(Util.format("%s %s:%d %s",
        uri, socketAddress, socketAddress.getPort(), ioe.getMessage()));
  }

  @Override public String toString() {
    return "RecordingProxySelector";
  }
}
