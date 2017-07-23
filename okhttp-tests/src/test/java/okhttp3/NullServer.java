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
package okhttp3;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import javax.net.ServerSocketFactory;
import okhttp3.internal.Util;

public final class NullServer {
  private InetSocketAddress address;
  private ServerSocket nullServer;

  public void start() throws Exception {
    nullServer = ServerSocketFactory.getDefault().createServerSocket();
    nullServer.bind(address, 0);
    address = new InetSocketAddress(InetAddress.getByName("localhost"), nullServer.getLocalPort());
  }

  public void shutdown() {
    Util.closeQuietly(nullServer);
  }

  public HttpUrl url(String scheme) {
    return new HttpUrl.Builder()
        .scheme(scheme)
        .host(address.getHostName())
        .port(nullServer.getLocalPort())
        .build();
  }

  public InetSocketAddress address() {
    return address;
  }
}
