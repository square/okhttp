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
package com.squareup.okhttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * An SSLSocketFactory that delegates calls. Sockets created by the delegate are wrapped with ones
 * that will not accept the {@link #TLS_FALLBACK_SCSV} cipher, thus bypassing server-side fallback
 * checks on platforms that support it. Unfortunately this wrapping will disable any
 * reflection-based calls to SSLSocket from Platform. This class also enables specified TLS
 * protocols, as some platforms do not enable all protocols by default. e.g. OpenJDK 7 only enables
 * TLSv1 by default.
 */
public class FallbackTestClientSocketFactory extends DelegatingSSLSocketFactory {
  /**
   * The cipher suite used during TLS connection fallback to indicate a fallback.
   * See https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
   */
  public static final String TLS_FALLBACK_SCSV = "TLS_FALLBACK_SCSV";

  private final String[] enabledProtocols;

  public FallbackTestClientSocketFactory(SSLSocketFactory delegate, String[] enabledProtocols) {
    super(delegate);
    this.enabledProtocols = enabledProtocols;
  }

  @Override public SSLSocket createSocket(Socket s, String host, int port, boolean autoClose)
      throws IOException {
    SSLSocket socket = super.createSocket(s, host, port, autoClose);
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override public SSLSocket createSocket() throws IOException {
    SSLSocket socket = super.createSocket();
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override public SSLSocket createSocket(String host,int port) throws IOException {
    SSLSocket socket = super.createSocket(host, port);
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override public SSLSocket createSocket(String host,int port, InetAddress localHost,
      int localPort) throws IOException {
    SSLSocket socket = super.createSocket(host, port, localHost, localPort);
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override public SSLSocket createSocket(InetAddress host,int port) throws IOException {
    SSLSocket socket = super.createSocket(host, port);
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override public SSLSocket createSocket(InetAddress address,int port,
      InetAddress localAddress, int localPort) throws IOException {
    SSLSocket socket = super.createSocket(address, port, localAddress, localPort);
    return new TlsFallbackScsvDisabledSSLSocket(socket);
  }

  @Override
  protected void configureSocket(SSLSocket sslSocket) throws IOException {
    // In order to test fallback we need at least two TLS versions available.
    sslSocket.setEnabledProtocols(enabledProtocols);
  }

  private static class TlsFallbackScsvDisabledSSLSocket extends DelegatingSSLSocket {

    public TlsFallbackScsvDisabledSSLSocket(SSLSocket socket) {
      super(socket);
    }

    @Override public void setEnabledCipherSuites(String[] suites) {
      List<String> enabledCipherSuites = new ArrayList<String>(suites.length);
      for (String suite : suites) {
        if (!suite.equals(TLS_FALLBACK_SCSV)) {
          enabledCipherSuites.add(suite);
        }
      }
      delegate.setEnabledCipherSuites(
          enabledCipherSuites.toArray(new String[enabledCipherSuites.size()]));
    }
  }
}
