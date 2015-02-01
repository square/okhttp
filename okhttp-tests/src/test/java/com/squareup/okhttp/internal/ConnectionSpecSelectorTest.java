/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.internal;

import com.squareup.okhttp.ConnectionSpec;
import com.squareup.okhttp.TlsVersion;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionSpecSelectorTest {

  static {
    Internal.initializeInstanceForTests();
  }

  private static final SSLContext sslContext = SslContextBuilder.localhost();

  public static final SSLHandshakeException RETRYABLE_EXCEPTION = new SSLHandshakeException(
      "Simulated handshake exception");

  @Test
  public void nonRetryableIOException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(
            ConnectionSpec.TLS_1_2_AND_BELOW, ConnectionSpec.TLS_1_1_AND_BELOW,
            ConnectionSpec.TLS_1_0_ONLY);
    SSLSocket socket =
        createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    try {
      connectionSpecSelector.configureSecureSocket(socket);

      boolean retry = connectionSpecSelector.connectionFailed(
          new IOException("Non-handshake exception"));
      assertFalse(retry);
    } finally {
      socket.close();
    }
  }

  @Test
  public void nonRetryableSSLHandshakeException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(
            ConnectionSpec.TLS_1_2_AND_BELOW, ConnectionSpec.TLS_1_1_AND_BELOW,
            ConnectionSpec.TLS_1_0_ONLY);
    SSLSocket socket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    try {
      connectionSpecSelector.configureSecureSocket(socket);

      SSLHandshakeException trustIssueException =
          new SSLHandshakeException("Certificate handshake exception");
      trustIssueException.initCause(new CertificateException());
      boolean retry = connectionSpecSelector.connectionFailed(trustIssueException);
      assertFalse(retry);
    } finally {
      socket.close();
    }
  }

  @Test
  public void retryableSSLHandshakeException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(
            ConnectionSpec.TLS_1_2_AND_BELOW, ConnectionSpec.TLS_1_1_AND_BELOW,
            ConnectionSpec.TLS_1_0_ONLY);
    SSLSocket socket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    try {
      connectionSpecSelector.configureSecureSocket(socket);

      boolean retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
      assertTrue(retry);
    } finally {
      socket.close();
    }
  }

  @Test
  public void someFallbacksSupported() throws Exception {
    ConnectionSpec tls12 =
        new ConnectionSpec.Builder(ConnectionSpec.TLS_1_0_ONLY)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0,
                TlsVersion.SSL_3_0)
            .build();
    ConnectionSpec tls11 =
        new ConnectionSpec.Builder(ConnectionSpec.TLS_1_0_ONLY)
            .tlsVersions(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0, TlsVersion.SSL_3_0)
            .build();
    ConnectionSpec tls10 =
        new ConnectionSpec.Builder(ConnectionSpec.TLS_1_0_ONLY)
            .tlsVersions(TlsVersion.TLS_1_0, TlsVersion.SSL_3_0)
            .build();
    ConnectionSpec sslV3 =
        new ConnectionSpec.Builder(ConnectionSpec.TLS_1_0_ONLY)
            .tlsVersions(TlsVersion.SSL_3_0)
            .build();

    ConnectionSpec[] connectionSpecs = { tls12, tls11, tls10, sslV3 };
    ConnectionSpecSelector connectionSpecSelector = createConnectionSpecSelector(connectionSpecs);

    TlsVersion[] enabledSocketTlsVersions = { TlsVersion.TLS_1_1, TlsVersion.TLS_1_0 };
    SSLSocket socket = createSocketWithEnabledProtocols(enabledSocketTlsVersions);

    // connectionSpecs[0] is not used because TLS_1_2 is not enabled.

    // connectionSpecs[1] is used here.
    try {
      connectionSpecSelector.configureSecureSocket(socket);
      assertEnabledProtocols(socket, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);

      boolean retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
      assertTrue(retry);
    } finally {
      socket.close();
    }

    // connectionSpecs[2] is used here.
    socket = createSocketWithEnabledProtocols(enabledSocketTlsVersions);
    try {
      connectionSpecSelector.configureSecureSocket(socket);
      assertEnabledProtocols(socket, TlsVersion.TLS_1_0);

      boolean retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
      assertFalse(retry);
    } finally {
      socket.close();
    }

    // connectionSpecs[3] is not used because SSLv3 is not enabled.
  }

  private static ConnectionSpecSelector createConnectionSpecSelector(ConnectionSpec... connectionSpecs) {
    return new ConnectionSpecSelector(Arrays.asList(connectionSpecs));
  }

  private SSLSocket createSocketWithEnabledProtocols(TlsVersion... tlsVersions) throws IOException {
    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
    socket.setEnabledProtocols(TlsVersion.javaNames(tlsVersions));
    return socket;
  }

  private static void assertEnabledProtocols(SSLSocket socket, TlsVersion... required) {
    Set<String> actual = new HashSet<String>(Arrays.asList(socket.getEnabledProtocols()));
    Set<String> expected = new HashSet<String>(Arrays.asList(TlsVersion.javaNames(required)));
    assertEquals(expected, actual);
  }
}
