/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Set;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/** An {@link SSLSocket} that delegates all calls. */
public abstract class DelegatingSSLSocket extends SSLSocket {
  protected final SSLSocket delegate;

  public DelegatingSSLSocket(SSLSocket delegate) {
    this.delegate = delegate;
  }

  @Override public void shutdownInput() throws IOException {
    delegate.shutdownInput();
  }

  @Override public void shutdownOutput() throws IOException {
    delegate.shutdownOutput();
  }

  @Override public String[] getSupportedCipherSuites() {
    return delegate.getSupportedCipherSuites();
  }

  @Override public String[] getEnabledCipherSuites() {
    return delegate.getEnabledCipherSuites();
  }

  @Override public void setEnabledCipherSuites(String[] suites) {
    delegate.setEnabledCipherSuites(suites);
  }

  @Override public String[] getSupportedProtocols() {
    return delegate.getSupportedProtocols();
  }

  @Override public String[] getEnabledProtocols() {
    return delegate.getEnabledProtocols();
  }

  @Override public void setEnabledProtocols(String[] protocols) {
    delegate.setEnabledProtocols(protocols);
  }

  @Override public SSLSession getSession() {
    return delegate.getSession();
  }

  @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
    delegate.addHandshakeCompletedListener(listener);
  }

  @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
    delegate.removeHandshakeCompletedListener(listener);
  }

  @Override public void startHandshake() throws IOException {
    delegate.startHandshake();
  }

  @Override public void setUseClientMode(boolean mode) {
    delegate.setUseClientMode(mode);
  }

  @Override public boolean getUseClientMode() {
    return delegate.getUseClientMode();
  }

  @Override public void setNeedClientAuth(boolean need) {
    delegate.setNeedClientAuth(need);
  }

  @Override public void setWantClientAuth(boolean want) {
    delegate.setWantClientAuth(want);
  }

  @Override public boolean getNeedClientAuth() {
    return delegate.getNeedClientAuth();
  }

  @Override public boolean getWantClientAuth() {
    return delegate.getWantClientAuth();
  }

  @Override public void setEnableSessionCreation(boolean flag) {
    delegate.setEnableSessionCreation(flag);
  }

  @Override public boolean getEnableSessionCreation() {
    return delegate.getEnableSessionCreation();
  }

  @Override public SSLParameters getSSLParameters() {
    return delegate.getSSLParameters();
  }

  @Override public void setSSLParameters(SSLParameters p) {
    delegate.setSSLParameters(p);
  }

  @Override public void close() throws IOException {
    delegate.close();
  }

  @Override public InetAddress getInetAddress() {
    return delegate.getInetAddress();
  }

  @Override public InputStream getInputStream() throws IOException {
    return delegate.getInputStream();
  }

  @Override public boolean getKeepAlive() throws SocketException {
    return delegate.getKeepAlive();
  }

  @Override public InetAddress getLocalAddress() {
    return delegate.getLocalAddress();
  }

  @Override public int getLocalPort() {
    return delegate.getLocalPort();
  }

  @Override public OutputStream getOutputStream() throws IOException {
    return delegate.getOutputStream();
  }

  @Override public int getPort() {
    return delegate.getPort();
  }

  @Override public int getSoLinger() throws SocketException {
    return delegate.getSoLinger();
  }

  @Override public int getReceiveBufferSize() throws SocketException {
    return delegate.getReceiveBufferSize();
  }

  @Override public int getSendBufferSize() throws SocketException {
    return delegate.getSendBufferSize();
  }

  @Override public int getSoTimeout() throws SocketException {
    return delegate.getSoTimeout();
  }

  @Override public boolean getTcpNoDelay() throws SocketException {
    return delegate.getTcpNoDelay();
  }

  @Override public void setKeepAlive(boolean keepAlive) throws SocketException {
    delegate.setKeepAlive(keepAlive);
  }

  @Override public void setSendBufferSize(int size) throws SocketException {
    delegate.setSendBufferSize(size);
  }

  @Override public void setReceiveBufferSize(int size) throws SocketException {
    delegate.setReceiveBufferSize(size);
  }

  @Override public void setSoLinger(boolean on, int timeout) throws SocketException {
    delegate.setSoLinger(on, timeout);
  }

  @Override public void setSoTimeout(int timeout) throws SocketException {
    delegate.setSoTimeout(timeout);
  }

  @Override public void setTcpNoDelay(boolean on) throws SocketException {
    delegate.setTcpNoDelay(on);
  }

  @Override public String toString() {
    return delegate.toString();
  }

  @Override public SocketAddress getLocalSocketAddress() {
    return delegate.getLocalSocketAddress();
  }

  @Override public SocketAddress getRemoteSocketAddress() {
    return delegate.getRemoteSocketAddress();
  }

  @Override public boolean isBound() {
    return delegate.isBound();
  }

  @Override public boolean isConnected() {
    return delegate.isConnected();
  }

  @Override public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override public void bind(SocketAddress localAddr) throws IOException {
    delegate.bind(localAddr);
  }

  @Override public void connect(SocketAddress remoteAddr) throws IOException {
    delegate.connect(remoteAddr);
  }

  @Override public void connect(SocketAddress remoteAddr, int timeout) throws IOException {
    delegate.connect(remoteAddr, timeout);
  }

  @Override public boolean isInputShutdown() {
    return delegate.isInputShutdown();
  }

  @Override public boolean isOutputShutdown() {
    return delegate.isOutputShutdown();
  }

  @Override public void setReuseAddress(boolean reuse) throws SocketException {
    delegate.setReuseAddress(reuse);
  }

  @Override public boolean getReuseAddress() throws SocketException {
    return delegate.getReuseAddress();
  }

  @Override public void setOOBInline(boolean oobinline) throws SocketException {
    delegate.setOOBInline(oobinline);
  }

  @Override public boolean getOOBInline() throws SocketException {
    return delegate.getOOBInline();
  }

  @Override public void setTrafficClass(int value) throws SocketException {
    delegate.setTrafficClass(value);
  }

  @Override public int getTrafficClass() throws SocketException {
    return delegate.getTrafficClass();
  }

  @Override public void sendUrgentData(int value) throws IOException {
    delegate.sendUrgentData(value);
  }

  @Override public SocketChannel getChannel() {
    return delegate.getChannel();
  }

  @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
  }

  // Java 9 methods.

  @SuppressWarnings("MissingOverride") // Can only override with JDK 9+
  public SSLSession getHandshakeSession() {
    try {
      return (SSLSession) SSLSocket.class.getMethod("getHandshakeSession").invoke(delegate);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  @SuppressWarnings("MissingOverride") // Can only override with JDK 9+
  public String getApplicationProtocol() {
    try {
      return (String) SSLSocket.class.getMethod("getApplicationProtocol").invoke(delegate);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  @SuppressWarnings("MissingOverride") // Can only override with JDK 9+
  public String getHandshakeApplicationProtocol() {
    try {
      return (String) SSLSocket.class.getMethod("getHandshakeApplicationProtocol").invoke(delegate);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  @SuppressWarnings("MissingOverride") // Can only override with JDK 9+
  public <T> Socket setOption(SocketOption<T> name, T value) throws IOException {
    try {
      SSLSocket.class.getMethod("setOption", SocketOption.class, Object.class).invoke(delegate, name, value);
      return this;
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  @SuppressWarnings({
      "MissingOverride", // Can only override with JDK 9+
      "unchecked" // Using reflection to delegate.
  })
  public <T> T getOption(SocketOption<T> name) throws IOException {
    try {
      return (T) SSLSocket.class.getMethod("getOption", SocketOption.class).invoke(delegate, name);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  @SuppressWarnings({
      "MissingOverride", // Can only override with JDK 9+
      "unchecked" // Using reflection to delegate.
  })
  public Set<SocketOption<?>> supportedOptions() {
    try {
      return (Set<SocketOption<?>>) SSLSocket.class.getMethod("supportedOptions").invoke(delegate);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new AssertionError();
    }
  }
}
