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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import jnr.unixsocket.UnixSocket;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

/**
 * Subtype UNIX socket for a higher-fidelity impersonation of TCP sockets.
 *
 * <p>This class doesn't pass {@link SelectableChannel} implementations to create input and output
 * streams. Though that type isn't in the public API, if the channel passed in implements that
 * interface then additional synchronization is used. This additional synchronization harms
 * concurrency and can cause deadlocks.
 *
 * <p>This class remembers which socket address was connected so that a non-null value can be
 * returned on calls to {@link #getInetAddress}.
 */
final class BlockingUnixSocket extends UnixSocket {
  private final File path;
  private final InputStream in;
  private final OutputStream out;
  private InetSocketAddress inetSocketAddress;

  BlockingUnixSocket(File path, UnixSocketChannel channel) {
    super(channel);
    this.path = path;
    this.in = Channels.newInputStream(new UnselectableReadableByteChannel());
    this.out = Channels.newOutputStream(new UnselectableWritableByteChannel());
  }

  BlockingUnixSocket(File path, UnixSocketChannel channel, InetSocketAddress address) {
    this(path, channel);
    this.inetSocketAddress = address;
  }

  @Override public void connect(SocketAddress endpoint) throws IOException {
    connect(endpoint, Integer.valueOf(0));
  }

  @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
    connect(endpoint, Integer.valueOf(timeout));
  }

  @Override public void connect(SocketAddress endpoint, Integer timeout) throws IOException {
    this.inetSocketAddress = (InetSocketAddress) endpoint;
    super.connect(new UnixSocketAddress(path), timeout);
  }

  @Override public InetAddress getInetAddress() {
    return inetSocketAddress.getAddress(); // TODO(jwilson): fake the remote address?
  }

  @Override public InputStream getInputStream() throws IOException {
    if (!isConnected()) throw new IOException("not connected");
    return in;
  }

  @Override public OutputStream getOutputStream() throws IOException {
    if (!isConnected()) throw new IOException("not connected");
    return out;
  }

  /** A readable byte channel that doesn't implement {@link SelectableChannel}. */
  final class UnselectableReadableByteChannel implements ReadableByteChannel {
    @Override public int read(ByteBuffer dst) throws IOException {
      return getChannel().read(dst);
    }

    @Override public boolean isOpen() {
      return getChannel().isOpen();
    }

    @Override public void close() throws IOException {
      getChannel().close();
    }
  }

  /** A writable byte channel that doesn't implement {@link SelectableChannel}. */
  final class UnselectableWritableByteChannel implements WritableByteChannel {
    @Override public int write(ByteBuffer src) throws IOException {
      return getChannel().write(src);
    }

    @Override public boolean isOpen() {
      return getChannel().isOpen();
    }

    @Override public void close() throws IOException {
      getChannel().close();
    }
  }
}
