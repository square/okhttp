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
package okhttp3.internal;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import okhttp3.ConnectionSpec;
import okhttp3.TlsVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionSpecSelectorTest {
  static {
    Internal.initializeInstanceForTests();
  }

  public static final SSLHandshakeException RETRYABLE_EXCEPTION = new SSLHandshakeException(
      "Simulated handshake exception");

  private SSLContext sslContext = SslContextBuilder.localhost();

  @Test
  public void nonRetryableIOException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS);
    SSLSocket socket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    connectionSpecSelector.configureSecureSocket(socket);

    boolean retry = connectionSpecSelector.connectionFailed(
        new IOException("Non-handshake exception"));
    assertFalse(retry);
    socket.close();
  }

  @Test
  public void nonRetryableSSLHandshakeException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS);
    SSLSocket socket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    connectionSpecSelector.configureSecureSocket(socket);

    SSLHandshakeException trustIssueException =
        new SSLHandshakeException("Certificate handshake exception");
    trustIssueException.initCause(new CertificateException());
    boolean retry = connectionSpecSelector.connectionFailed(trustIssueException);
    assertFalse(retry);
    socket.close();
  }

  @Test
  public void retryableSSLHandshakeException() throws Exception {
    ConnectionSpecSelector connectionSpecSelector =
        createConnectionSpecSelector(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS);
    SSLSocket socket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);
    connectionSpecSelector.configureSecureSocket(socket);

    boolean retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
    assertTrue(retry);
    socket.close();
  }

  @Test
  public void someFallbacksSupported() throws Exception {
    ConnectionSpec sslV3 =
        new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.SSL_3_0)
            .build();

    ConnectionSpecSelector connectionSpecSelector = createConnectionSpecSelector(
        ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, sslV3);

    TlsVersion[] enabledSocketTlsVersions = {TlsVersion.TLS_1_1, TlsVersion.TLS_1_0};
    SSLSocket socket = createSocketWithEnabledProtocols(enabledSocketTlsVersions);

    // MODERN_TLS is used here.
    connectionSpecSelector.configureSecureSocket(socket);
    assertEnabledProtocols(socket, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0);

    boolean retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
    assertTrue(retry);
    socket.close();

    // COMPATIBLE_TLS is used here.
    socket = createSocketWithEnabledProtocols(enabledSocketTlsVersions);
    connectionSpecSelector.configureSecureSocket(socket);
    assertEnabledProtocols(socket, TlsVersion.TLS_1_0);

    retry = connectionSpecSelector.connectionFailed(RETRYABLE_EXCEPTION);
    assertFalse(retry);
    socket.close();

    // sslV3 is not used because SSLv3 is not enabled on the socket.
  }

  private static ConnectionSpecSelector createConnectionSpecSelector(
      ConnectionSpec... connectionSpecs) {
    return new ConnectionSpecSelector(Arrays.asList(connectionSpecs));
  }

  private SSLSocket createSocketWithEnabledProtocols(TlsVersion... tlsVersions) throws IOException {
    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
    socket.setEnabledProtocols(javaNames(tlsVersions));
    return socket;
  }

  private static void assertEnabledProtocols(SSLSocket socket, TlsVersion... required) {
    Set<String> actual = new LinkedHashSet<>(Arrays.asList(socket.getEnabledProtocols()));
    Set<String> expected = new LinkedHashSet<>(Arrays.asList(javaNames(required)));
    assertEquals(expected, actual);
  }

  private static String[] javaNames(TlsVersion... tlsVersions) {
    String[] protocols = new String[tlsVersions.length];
    for (int i = 0; i < tlsVersions.length; i++) {
      protocols[i] = tlsVersions[i].javaName();
    }
    return protocols;
  }
}
