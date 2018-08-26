/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.internal.proxy;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * A proxy selector that always returns the {@link Proxy#NO_PROXY}.
 */
public class NullProxySelector extends ProxySelector {
  @Override public List<Proxy> select(URI uri) {
    if (uri == null) {
      throw new IllegalArgumentException("uri must not be null");
    }
    return Collections.singletonList(Proxy.NO_PROXY);
  }

  @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
  }
}
