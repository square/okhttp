/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.DelegatingSSLSocket;
import okhttp3.DelegatingSSLSocketFactory;
import okio.Buffer;
import okio.ByteString;

/** Records all bytes written and read from a socket and makes them available for inspection. */
public final class SocketRecorder {
  private final Deque<RecordedSocket> recordedSockets = new LinkedBlockingDeque<>();

  /** Returns an SSLSocketFactory whose sockets will record all transmitted bytes. */
  public SSLSocketFactory sslSocketFactory(SSLSocketFactory delegate) {
    return new DelegatingSSLSocketFactory(delegate) {
      @Override protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
        RecordedSocket recordedSocket = new RecordedSocket();
        recordedSockets.add(recordedSocket);
        return new RecordingSSLSocket(sslSocket, recordedSocket);
      }
    };
  }

  public RecordedSocket takeSocket() {
    return recordedSockets.remove();
  }

  /** A bidirectional transfer of unadulterated bytes over a socket. */
  public static final class RecordedSocket {
    private final Buffer bytesWritten = new Buffer();
    private final Buffer bytesRead = new Buffer();

    synchronized void byteWritten(int b) {
      bytesWritten.writeByte(b);
    }

    synchronized void byteRead(int b) {
      bytesRead.writeByte(b);
    }

    synchronized void bytesWritten(byte[] bytes, int offset, int length) {
      bytesWritten.write(bytes, offset, length);
    }

    synchronized void bytesRead(byte[] bytes, int offset, int length) {
      bytesRead.write(bytes, offset, length);
    }

    /** Returns all bytes that have been written to this socket. */
    public synchronized ByteString bytesWritten() {
      return bytesWritten.readByteString();
    }

    /** Returns all bytes that have been read from this socket. */
    public synchronized ByteString bytesRead() {
      return bytesRead.readByteString();
    }
  }

  static final class RecordingInputStream extends InputStream {
    private final Socket socket;
    private final RecordedSocket recordedSocket;

    RecordingInputStream(Socket socket, RecordedSocket recordedSocket) {
      this.socket = socket;
      this.recordedSocket = recordedSocket;
    }

    @Override public int read() throws IOException {
      int b = socket.getInputStream().read();
      if (b == -1) return -1;
      recordedSocket.byteRead(b);
      return b;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      int read = socket.getInputStream().read(b, off, len);
      if (read == -1) return -1;
      recordedSocket.bytesRead(b, off, read);
      return read;
    }

    @Override public void close() throws IOException {
      socket.getInputStream().close();
    }
  }

  static final class RecordingOutputStream extends OutputStream {
    private final Socket socket;
    private final RecordedSocket recordedSocket;

    RecordingOutputStream(Socket socket, RecordedSocket recordedSocket) {
      this.socket = socket;
      this.recordedSocket = recordedSocket;
    }

    @Override public void write(int b) throws IOException {
      socket.getOutputStream().write(b);
      recordedSocket.byteWritten(b);
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
      socket.getOutputStream().write(b, off, len);
      recordedSocket.bytesWritten(b, off, len);
    }

    @Override public void close() throws IOException {
      socket.getOutputStream().close();
    }

    @Override public void flush() throws IOException {
      socket.getOutputStream().flush();
    }
  }

  static final class RecordingSSLSocket extends DelegatingSSLSocket {
    private final InputStream inputStream;
    private final OutputStream outputStream;

    RecordingSSLSocket(SSLSocket delegate, RecordedSocket recordedSocket) {
      super(delegate);
      inputStream = new RecordingInputStream(delegate, recordedSocket);
      outputStream = new RecordingOutputStream(delegate, recordedSocket);
    }

    @Override public void startHandshake() throws IOException {
      // Intercept the handshake to properly configure TLS extensions with Jetty ALPN. Jetty ALPN
      // expects the real SSLSocket to be placed in the global map. Because we are wrapping the real
      // SSLSocket, it confuses Jetty ALPN. This patches that up so things work as expected.
      Class<?> alpn = null;
      Class<?> provider = null;
      try {
        alpn = Class.forName("org.eclipse.jetty.alpn.ALPN");
        provider = Class.forName("org.eclipse.jetty.alpn.ALPN$Provider");
      } catch (ClassNotFoundException ignored) {
      }

      if (alpn == null || provider == null) {
        // No Jetty, so nothing to worry about.
        super.startHandshake();
        return;
      }

      Object providerInstance = null;
      Method putMethod = null;
      try {
        Method getMethod = alpn.getMethod("get", SSLSocket.class);
        putMethod = alpn.getMethod("put", SSLSocket.class, provider);
        providerInstance = getMethod.invoke(null, this);
        if (providerInstance == null) {
          // Jetty's on the classpath but TLS extensions weren't used.
          super.startHandshake();
          return;
        }

        // TLS extensions were used; replace with the real SSLSocket to make Jetty ALPN happy.
        putMethod.invoke(null, delegate, providerInstance);
        super.startHandshake();
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        throw new AssertionError();
      } finally {
        // If we replaced the SSLSocket in the global map, we must put the original back for
        // everything to work inside OkHttp.
        if (providerInstance != null) {
          try {
            putMethod.invoke(null, this, providerInstance);
          } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError();
          }
        }
      }
    }

    @Override public InputStream getInputStream() throws IOException {
      return inputStream;
    }

    @Override public OutputStream getOutputStream() throws IOException {
      return outputStream;
    }
  }
}
