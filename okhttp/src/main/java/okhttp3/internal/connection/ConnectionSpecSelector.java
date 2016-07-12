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
package okhttp3.internal.connection;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.UnknownServiceException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocket;
import okhttp3.ConnectionSpec;
import okhttp3.internal.Internal;

/**
 * Handles the connection spec fallback strategy: When a secure socket connection fails due to a
 * handshake / protocol problem the connection may be retried with different protocols. Instances
 * are stateful and should be created and used for a single connection attempt.
 */
public final class ConnectionSpecSelector {

  private final List<ConnectionSpec> connectionSpecs;
  private int nextModeIndex;
  private boolean isFallbackPossible;
  private boolean isFallback;

  public ConnectionSpecSelector(List<ConnectionSpec> connectionSpecs) {
    this.nextModeIndex = 0;
    this.connectionSpecs = connectionSpecs;
  }

  /**
   * Configures the supplied {@link SSLSocket} to connect to the specified host using an appropriate
   * {@link ConnectionSpec}. Returns the chosen {@link ConnectionSpec}, never {@code null}.
   *
   * @throws IOException if the socket does not support any of the TLS modes available
   */
  public ConnectionSpec configureSecureSocket(SSLSocket sslSocket) throws IOException {
    ConnectionSpec tlsConfiguration = null;
    for (int i = nextModeIndex, size = connectionSpecs.size(); i < size; i++) {
      ConnectionSpec connectionSpec = connectionSpecs.get(i);
      if (connectionSpec.isCompatible(sslSocket)) {
        tlsConfiguration = connectionSpec;
        nextModeIndex = i + 1;
        break;
      }
    }

    if (tlsConfiguration == null) {
      // This may be the first time a connection has been attempted and the socket does not support
      // any the required protocols, or it may be a retry (but this socket supports fewer
      // protocols than was suggested by a prior socket).
      throw new UnknownServiceException(
          "Unable to find acceptable protocols. isFallback=" + isFallback
              + ", modes=" + connectionSpecs
              + ", supported protocols=" + Arrays.toString(sslSocket.getEnabledProtocols()));
    }

    isFallbackPossible = isFallbackPossible(sslSocket);

    Internal.instance.apply(tlsConfiguration, sslSocket, isFallback);

    return tlsConfiguration;
  }

  /**
   * Reports a failure to complete a connection. Determines the next {@link ConnectionSpec} to try,
   * if any.
   *
   * @return {@code true} if the connection should be retried using {@link
   * #configureSecureSocket(SSLSocket)} or {@code false} if not
   */
  public boolean connectionFailed(IOException e) {
    // Any future attempt to connect using this strategy will be a fallback attempt.
    isFallback = true;

    if (!isFallbackPossible) {
      return false;
    }

    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption or timeout (SocketTimeoutException), don't recover.
    // For the socket connect timeout case we do not try the same host with a different
    // ConnectionSpec: we assume it is unreachable.
    if (e instanceof InterruptedIOException) {
      return false;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different connection spec.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // On Android, SSLProtocolExceptions can be caused by TLS_FALLBACK_SCSV failures, which means we
    // retry those when we probably should not.
    return (e instanceof SSLHandshakeException || e instanceof SSLProtocolException);
  }

  /**
   * Returns {@code true} if any later {@link ConnectionSpec} in the fallback strategy looks
   * possible based on the supplied {@link SSLSocket}. It assumes that a future socket will have the
   * same capabilities as the supplied socket.
   */
  private boolean isFallbackPossible(SSLSocket socket) {
    for (int i = nextModeIndex; i < connectionSpecs.size(); i++) {
      if (connectionSpecs.get(i).isCompatible(socket)) {
        return true;
      }
    }
    return false;
  }
}
