/*
 * Copyright (C) 2013 Square, Inc.
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

import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import okhttp3.internal.Util;

/**
 * A record of a TLS handshake. For HTTPS clients, the client is <i>local</i> and the remote server
 * is its <i>peer</i>.
 *
 * <p>This value object describes a completed handshake. Use {@link ConnectionSpec} to set policy
 * for new handshakes.
 */
public final class Handshake {
  private final TlsVersion tlsVersion;
  private final CipherSuite cipherSuite;
  private final List<Certificate> peerCertificates;
  private final List<Certificate> localCertificates;

  private Handshake(TlsVersion tlsVersion, CipherSuite cipherSuite,
      List<Certificate> peerCertificates, List<Certificate> localCertificates) {
    this.tlsVersion = tlsVersion;
    this.cipherSuite = cipherSuite;
    this.peerCertificates = peerCertificates;
    this.localCertificates = localCertificates;
  }

  public static Handshake get(SSLSession session) {
    String cipherSuiteString = session.getCipherSuite();
    if (cipherSuiteString == null) throw new IllegalStateException("cipherSuite == null");
    CipherSuite cipherSuite = CipherSuite.forJavaName(cipherSuiteString);

    String tlsVersionString = session.getProtocol();
    if (tlsVersionString == null) throw new IllegalStateException("tlsVersion == null");
    TlsVersion tlsVersion = TlsVersion.forJavaName(tlsVersionString);

    Certificate[] peerCertificates;
    try {
      peerCertificates = session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException ignored) {
      peerCertificates = null;
    }
    List<Certificate> peerCertificatesList = peerCertificates != null
        ? Util.immutableList(peerCertificates)
        : Collections.<Certificate>emptyList();

    Certificate[] localCertificates = session.getLocalCertificates();
    List<Certificate> localCertificatesList = localCertificates != null
        ? Util.immutableList(localCertificates)
        : Collections.<Certificate>emptyList();

    return new Handshake(tlsVersion, cipherSuite, peerCertificatesList, localCertificatesList);
  }

  public static Handshake get(TlsVersion tlsVersion, CipherSuite cipherSuite,
      List<Certificate> peerCertificates, List<Certificate> localCertificates) {
    if (cipherSuite == null) throw new NullPointerException("cipherSuite == null");
    return new Handshake(tlsVersion, cipherSuite, Util.immutableList(peerCertificates),
        Util.immutableList(localCertificates));
  }

  /**
   * Returns the TLS version used for this connection. May return null if the response was cached
   * with a version of OkHttp prior to 3.0.
   */
  public TlsVersion tlsVersion() {
    return tlsVersion;
  }

  /** Returns the cipher suite used for the connection. */
  public CipherSuite cipherSuite() {
    return cipherSuite;
  }

  /** Returns a possibly-empty list of certificates that identify the remote peer. */
  public List<Certificate> peerCertificates() {
    return peerCertificates;
  }

  /** Returns the remote peer's principle, or null if that peer is anonymous. */
  public Principal peerPrincipal() {
    return !peerCertificates.isEmpty()
        ? ((X509Certificate) peerCertificates.get(0)).getSubjectX500Principal()
        : null;
  }

  /** Returns a possibly-empty list of certificates that identify this peer. */
  public List<Certificate> localCertificates() {
    return localCertificates;
  }

  /** Returns the local principle, or null if this peer is anonymous. */
  public Principal localPrincipal() {
    return !localCertificates.isEmpty()
        ? ((X509Certificate) localCertificates.get(0)).getSubjectX500Principal()
        : null;
  }

  @Override public boolean equals(Object other) {
    if (!(other instanceof Handshake)) return false;
    Handshake that = (Handshake) other;
    return Util.equal(cipherSuite, that.cipherSuite)
        && cipherSuite.equals(that.cipherSuite)
        && peerCertificates.equals(that.peerCertificates)
        && localCertificates.equals(that.localCertificates);
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + (tlsVersion != null ? tlsVersion.hashCode() : 0);
    result = 31 * result + cipherSuite.hashCode();
    result = 31 * result + peerCertificates.hashCode();
    result = 31 * result + localCertificates.hashCode();
    return result;
  }
}
