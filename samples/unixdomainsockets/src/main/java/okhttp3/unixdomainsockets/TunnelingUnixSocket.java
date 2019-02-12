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
import java.net.SocketAddress;
import jnr.unixsocket.UnixSocket;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

/**
 * Subtype UNIX socket for a higher-fidelity impersonation of TCP sockets. This is named "tunneling"
 * because it assumes the ultimate destination has a hostname and port.
 */
final class TunnelingUnixSocket extends UnixSocket {
  private final File path;
  private InetSocketAddress inetSocketAddress;

  TunnelingUnixSocket(File path, UnixSocketChannel channel) {
    super(channel);
    this.path = path;
  }

  TunnelingUnixSocket(File path, UnixSocketChannel channel, InetSocketAddress address) {
    this(path, channel);
    this.inetSocketAddress = address;
  }

  @Override public void connect(SocketAddress endpoint) throws IOException {
    this.inetSocketAddress = (InetSocketAddress) endpoint;
    super.connect(new UnixSocketAddress(path), 0);
  }

  @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
    this.inetSocketAddress = (InetSocketAddress) endpoint;
    super.connect(new UnixSocketAddress(path), timeout);
  }

  @Override public InetAddress getInetAddress() {
    return inetSocketAddress.getAddress();
  }
}
