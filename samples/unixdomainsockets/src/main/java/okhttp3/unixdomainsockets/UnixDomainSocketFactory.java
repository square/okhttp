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
package okhttp3.unixdomainsockets;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import jnr.unixsocket.UnixSocketChannel;

/** Impersonate TCP-style SocketFactory over UNIX domain sockets. */
public final class UnixDomainSocketFactory extends SocketFactory {
  private final File path;

  public UnixDomainSocketFactory(File path) {
    this.path = path;
  }

  @Override public Socket createSocket() throws IOException {
    UnixSocketChannel channel = UnixSocketChannel.open();
    return new TunnelingUnixSocket(path, channel);
  }

  @Override public Socket createSocket(String host, int port) throws IOException {
    Socket result = createSocket();
    result.connect(new InetSocketAddress(host, port));
    return result;
  }

  @Override public Socket createSocket(
      String host, int port, InetAddress localHost, int localPort) throws IOException {
    return createSocket(host, port);
  }

  @Override public Socket createSocket(InetAddress host, int port) throws IOException {
    Socket result = createSocket();
    result.connect(new InetSocketAddress(host, port));
    return result;
  }

  @Override public Socket createSocket(
      InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
    return createSocket(host, port);
  }
}
