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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.ServerSocketFactory;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocket;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

/** Impersonate TCP-style ServerSocketFactory over UNIX domain sockets. */
public final class UnixDomainServerSocketFactory extends ServerSocketFactory {
  private final File path;

  public UnixDomainServerSocketFactory(File path) {
    this.path = path;
  }

  private ServerSocket createUnixDomainSocket() throws IOException {
    return new UnixDomainServerSocket();
  }

  @Override public ServerSocket createServerSocket() throws IOException {
    return createUnixDomainSocket();
  }

  @Override public ServerSocket createServerSocket(int port) throws IOException {
    return createUnixDomainSocket();
  }

  @Override public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    return createUnixDomainSocket();
  }

  @Override public ServerSocket createServerSocket(
      int port, int backlog, InetAddress inetAddress) throws IOException {
    return createUnixDomainSocket();
  }

  final class UnixDomainServerSocket extends ServerSocket {
    private UnixServerSocketChannel serverSocketChannel;
    private InetSocketAddress endpoint;

    UnixDomainServerSocket() throws IOException {
    }

    @Override public void bind(SocketAddress endpoint, int backlog) throws IOException {
      this.endpoint = (InetSocketAddress) endpoint;

      UnixSocketAddress address = new UnixSocketAddress(path);
      serverSocketChannel = UnixServerSocketChannel.open();
      serverSocketChannel.configureBlocking(true);
      serverSocketChannel.socket().bind(address);
    }

    @Override public int getLocalPort() {
      return 1; // A white lie. There is no local port.
    }

    @Override public SocketAddress getLocalSocketAddress() {
      return endpoint;
    }

    @Override public Socket accept() throws IOException {
      UnixSocketChannel socketChannel = serverSocketChannel.accept();

      return new UnixSocket(socketChannel) {
        @Override public InetAddress getInetAddress() {
          return endpoint.getAddress(); // TODO(jwilson): fake the remote address?
        }
      };
    }

    @Override public void close() throws IOException {
      serverSocketChannel.close();
    }
  }
}
