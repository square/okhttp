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
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import static okhttp3.internal.Util.closeQuietly;

/**
 * A limited implementation of SOCKS Protocol Version 5, intended to be similar to MockWebServer.
 * See <a href="https://www.ietf.org/rfc/rfc1928.txt">RFC 1928</a>.
 */
public final class SocksProxy {
  public static final String HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS = "onlyProxyCanResolveMe.org";

  private static final int VERSION_5 = 5;
  private static final int METHOD_NONE = 0xff;
  private static final int METHOD_NO_AUTHENTICATION_REQUIRED = 0;
  private static final int ADDRESS_TYPE_IPV4 = 1;
  private static final int ADDRESS_TYPE_DOMAIN_NAME = 3;
  private static final int COMMAND_CONNECT = 1;
  private static final int REPLY_SUCCEEDED = 0;

  private static final Logger logger = Logger.getLogger(SocksProxy.class.getName());

  private final ExecutorService executor = Executors.newCachedThreadPool(
      Util.threadFactory("SocksProxy", false));

  private ServerSocket serverSocket;
  private AtomicInteger connectionCount = new AtomicInteger();
  private final Set<Socket> openSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

  public void play() throws IOException {
    serverSocket = new ServerSocket(0);
    executor.execute(() -> {
      String threadName = "SocksProxy " + serverSocket.getLocalPort();
      Thread.currentThread().setName(threadName);
      try {
        while (true) {
          Socket socket = serverSocket.accept();
          connectionCount.incrementAndGet();
          service(socket);
        }
      } catch (SocketException e) {
        logger.info(threadName + " done accepting connections: " + e.getMessage());
      } catch (IOException e) {
        logger.log(Level.WARNING, threadName + " failed unexpectedly", e);
      } finally {
        for (Socket socket : openSockets) {
          closeQuietly(socket);
        }
        Thread.currentThread().setName("SocksProxy");
      }
    });
  }

  public Proxy proxy() {
    return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(
        "localhost", serverSocket.getLocalPort()));
  }

  public int connectionCount() {
    return connectionCount.get();
  }

  public void shutdown() throws Exception {
    serverSocket.close();
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IOException("Gave up waiting for executor to shut down");
    }
  }

  private void service(final Socket from) {
    executor.execute(() -> {
      String threadName = "SocksProxy " + from.getRemoteSocketAddress();
      Thread.currentThread().setName(threadName);
      try {
        BufferedSource fromSource = Okio.buffer(Okio.source(from));
        BufferedSink fromSink = Okio.buffer(Okio.sink(from));
        hello(fromSource, fromSink);
        acceptCommand(from.getInetAddress(), fromSource, fromSink);
        openSockets.add(from);
      } catch (IOException e) {
        logger.log(Level.WARNING, threadName + " failed", e);
        closeQuietly(from);
      } finally {
        Thread.currentThread().setName("SocksProxy");
      }
    });
  }

  private void hello(BufferedSource fromSource, BufferedSink fromSink) throws IOException {
    int version = fromSource.readByte() & 0xff;
    int methodCount = fromSource.readByte() & 0xff;
    int selectedMethod = METHOD_NONE;

    if (version != VERSION_5) {
      throw new ProtocolException("unsupported version: " + version);
    }

    for (int i = 0; i < methodCount; i++) {
      int candidateMethod = fromSource.readByte() & 0xff;
      if (candidateMethod == METHOD_NO_AUTHENTICATION_REQUIRED) {
        selectedMethod = candidateMethod;
      }
    }

    switch (selectedMethod) {
      case METHOD_NO_AUTHENTICATION_REQUIRED:
        fromSink.writeByte(VERSION_5);
        fromSink.writeByte(selectedMethod);
        fromSink.emit();
        break;

      default:
        throw new ProtocolException("unsupported method: " + selectedMethod);
    }
  }

  private void acceptCommand(InetAddress fromAddress, BufferedSource fromSource,
      BufferedSink fromSink) throws IOException {
    // Read the command.
    int version = fromSource.readByte() & 0xff;
    if (version != VERSION_5) throw new ProtocolException("unexpected version: " + version);
    int command = fromSource.readByte() & 0xff;
    int reserved = fromSource.readByte() & 0xff;
    if (reserved != 0) throw new ProtocolException("unexpected reserved: " + reserved);

    int addressType = fromSource.readByte() & 0xff;
    InetAddress toAddress;
    switch (addressType) {
      case ADDRESS_TYPE_IPV4:
        toAddress = InetAddress.getByAddress(fromSource.readByteArray(4L));
        break;

      case ADDRESS_TYPE_DOMAIN_NAME:
        int domainNameLength = fromSource.readByte() & 0xff;
        String domainName = fromSource.readUtf8(domainNameLength);
        // Resolve HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS to localhost.
        toAddress = domainName.equalsIgnoreCase(HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS)
            ? InetAddress.getByName("localhost")
            : InetAddress.getByName(domainName);
        break;

      default:
        throw new ProtocolException("unsupported address type: " + addressType);
    }

    int port = fromSource.readShort() & 0xffff;

    switch (command) {
      case COMMAND_CONNECT:
        Socket toSocket = new Socket(toAddress, port);
        byte[] localAddress = toSocket.getLocalAddress().getAddress();
        if (localAddress.length != 4) {
          throw new ProtocolException("unexpected address: " + toSocket.getLocalAddress());
        }

        // Write the reply.
        fromSink.writeByte(VERSION_5);
        fromSink.writeByte(REPLY_SUCCEEDED);
        fromSink.writeByte(0);
        fromSink.writeByte(ADDRESS_TYPE_IPV4);
        fromSink.write(localAddress);
        fromSink.writeShort(toSocket.getLocalPort());
        fromSink.emit();

        logger.log(Level.INFO, "SocksProxy connected " + fromAddress + " to " + toAddress);

        // Copy sources to sinks in both directions.
        BufferedSource toSource = Okio.buffer(Okio.source(toSocket));
        BufferedSink toSink = Okio.buffer(Okio.sink(toSocket));
        openSockets.add(toSocket);

        transfer(fromAddress, toAddress, fromSource, toSink);
        transfer(fromAddress, toAddress, toSource, fromSink);
        break;

      default:
        throw new ProtocolException("unexpected command: " + command);
    }
  }

  private void transfer(final InetAddress fromAddress, final InetAddress toAddress,
      final BufferedSource source, final BufferedSink sink) {
    executor.execute(() -> {
      String threadName = "SocksProxy " + fromAddress + " to " + toAddress;
      Thread.currentThread().setName(threadName);
      Buffer buffer = new Buffer();
      try {
        while (true) {
          long byteCount = source.read(buffer, 8192L);
          if (byteCount == -1L) break;
          sink.write(buffer, byteCount);
          sink.emit();
        }
      } catch (SocketException e) {
        logger.info(threadName + " done: " + e.getMessage());
      } catch (IOException e) {
        logger.log(Level.WARNING, threadName + " failed", e);
      }

      try {
        source.close();
      } catch (IOException e) {
        logger.log(Level.WARNING, threadName + " failed", e);
      }

      try {
        sink.close();
      } catch (IOException e) {
        logger.log(Level.WARNING, threadName + " failed", e);
      }

      Thread.currentThread().setName("SocksProxy");
    });
  }
}
