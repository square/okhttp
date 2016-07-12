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
package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/**
 * A {@link SocketFactory} that delegates calls. Sockets can be configured after creation by
 * overriding {@link #configureSocket(java.net.Socket)}.
 */
public class DelegatingSocketFactory extends SocketFactory {
  private final SocketFactory delegate;

  public DelegatingSocketFactory(SocketFactory delegate) {
    this.delegate = delegate;
  }

  @Override public Socket createSocket() throws IOException {
    Socket socket = delegate.createSocket();
    return configureSocket(socket);
  }

  @Override public Socket createSocket(String host, int port) throws IOException {
    Socket socket = delegate.createSocket(host, port);
    return configureSocket(socket);
  }

  @Override public Socket createSocket(String host, int port, InetAddress localAddress,
      int localPort) throws IOException {
    Socket socket = delegate.createSocket(host, port, localAddress, localPort);
    return configureSocket(socket);
  }

  @Override public Socket createSocket(InetAddress host, int port) throws IOException {
    Socket socket = delegate.createSocket(host, port);
    return configureSocket(socket);
  }

  @Override public Socket createSocket(InetAddress host, int port, InetAddress localAddress,
      int localPort) throws IOException {
    Socket socket = delegate.createSocket(host, port, localAddress, localPort);
    return configureSocket(socket);
  }

  protected Socket configureSocket(Socket socket) throws IOException {
    // No-op by default.
    return socket;
  }
}
