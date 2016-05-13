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
import java.net.ServerSocket;
import javax.net.ServerSocketFactory;

/**
 * A {@link ServerSocketFactory} that delegates calls. Sockets can be configured after creation by
 * overriding {@link #configureServerSocket(java.net.ServerSocket)}.
 */
public class DelegatingServerSocketFactory extends ServerSocketFactory {

  private final ServerSocketFactory delegate;

  public DelegatingServerSocketFactory(ServerSocketFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  public ServerSocket createServerSocket() throws IOException {
    ServerSocket serverSocket = delegate.createServerSocket();
    return configureServerSocket(serverSocket);
  }

  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    ServerSocket serverSocket = delegate.createServerSocket(port);
    return configureServerSocket(serverSocket);
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    ServerSocket serverSocket = delegate.createServerSocket(port, backlog);
    return configureServerSocket(serverSocket);
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress)
      throws IOException {
    ServerSocket serverSocket = delegate.createServerSocket(port, backlog, ifAddress);
    return configureServerSocket(serverSocket);
  }

  protected ServerSocket configureServerSocket(ServerSocket serverSocket) throws IOException {
    // No-op by default.
    return serverSocket;
  }
}
