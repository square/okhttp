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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For {@code
 * https:} URLs, this includes the TLS version and cipher suites to use when negotiating a secure
 * connection.
 */
public final class ConnectionSpec {

  // This is a subset of the cipher suites supported in Chrome 37, current as of 2014-10-5.
  // All of these suites are available on Android L; earlier releases support a subset of
  // these suites. https://github.com/square/okhttp/issues/330
  private static final CipherSuite[] APPROVED_CIPHER_SUITES = new CipherSuite[] {
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_RC4_128_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_RC4_128_SHA,
      CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_RC4_128_SHA,
      CipherSuite.TLS_RSA_WITH_RC4_128_MD5,
  };

  public static final ConnectionSpec TLS_1_2_AND_BELOW = new Builder(true)
      .cipherSuites(APPROVED_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  public static final ConnectionSpec TLS_1_1_AND_BELOW = new Builder(TLS_1_2_AND_BELOW)
      .tlsVersions(TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
      .build();

  public static final ConnectionSpec TLS_1_0_ONLY = new Builder(TLS_1_2_AND_BELOW)
      .tlsVersions(TlsVersion.TLS_1_0)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final ConnectionSpec CLEARTEXT = new Builder(false).build();

  final boolean tls;

  /**
   * Used if tls == true. The cipher suites to set on the SSLSocket. {@code null} means "use
   * default set".
   */
  private final String[] cipherSuites;

  /** Used if tls == true. The TLS protocol versions to use. */
  private final String[] tlsVersions;

  final boolean supportsTlsExtensions;

  private ConnectionSpec(Builder builder) {
    this.tls = builder.tls;
    this.cipherSuites = builder.cipherSuites;
    this.tlsVersions = builder.tlsVersions;
    this.supportsTlsExtensions = builder.supportsTlsExtensions;
  }

  public boolean isTls() {
    return tls;
  }

  /**
   * Returns the cipher suites to use for a connection. This method can return {@code null} if the
   * cipher suites enabled by default should be used.
   */
  public List<CipherSuite> cipherSuites() {
    if (cipherSuites == null) {
      return null;
    }
    CipherSuite[] result = new CipherSuite[cipherSuites.length];
    for (int i = 0; i < cipherSuites.length; i++) {
      result[i] = CipherSuite.forJavaName(cipherSuites[i]);
    }
    return Util.immutableList(result);
  }

  /**
   * Returns the TLS versions to use for a connection. Ordering is important. See {@link
   * #isCompatible(javax.net.ssl.SSLSocket)}.
   */
  public List<TlsVersion> tlsVersions() {
    TlsVersion[] result = new TlsVersion[tlsVersions.length];
    for (int i = 0; i < tlsVersions.length; i++) {
      result[i] = TlsVersion.forJavaName(tlsVersions[i]);
    }
    return Util.immutableList(result);
  }

  public boolean supportsTlsExtensions() {
    return supportsTlsExtensions;
  }

  /** Applies this spec to {@code sslSocket}. */
  void apply(SSLSocket sslSocket, boolean isFallback) {
    ConnectionSpec specToApply = supportedSpec(sslSocket, isFallback);

    sslSocket.setEnabledProtocols(specToApply.tlsVersions);

    String[] cipherSuitesToEnable = specToApply.cipherSuites;
    // null means "use default set".
    if (cipherSuitesToEnable != null) {
      sslSocket.setEnabledCipherSuites(cipherSuitesToEnable);
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not enabled by
   * {@code sslSocket}.
   */
  private ConnectionSpec supportedSpec(SSLSocket sslSocket, boolean isFallback) {
    String[] cipherSuitesToEnable = null;
    if (cipherSuites != null) {
      String[] cipherSuitesToSelectFrom = sslSocket.getEnabledCipherSuites();
      cipherSuitesToEnable =
          Util.intersect(String.class, cipherSuites, cipherSuitesToSelectFrom);
    }

    if (isFallback) {
      // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
      // the SCSV cipher is added to signal that a protocol fallback has taken place.
      final String fallbackScsv = "TLS_FALLBACK_SCSV";
      boolean socketSupportsFallbackScsv =
          Arrays.asList(sslSocket.getSupportedCipherSuites()).contains(fallbackScsv);

      if (socketSupportsFallbackScsv) {
        // Add the SCSV cipher to the set of enabled cipher suites iff it is supported.
        String[] oldEnabledCipherSuites = cipherSuitesToEnable != null
            ? cipherSuitesToEnable
            : sslSocket.getEnabledCipherSuites();
        String[] newEnabledCipherSuites = new String[oldEnabledCipherSuites.length + 1];
        System.arraycopy(oldEnabledCipherSuites, 0,
            newEnabledCipherSuites, 0, oldEnabledCipherSuites.length);
        newEnabledCipherSuites[newEnabledCipherSuites.length - 1] = fallbackScsv;
        cipherSuitesToEnable = newEnabledCipherSuites;
      }
    }

    String[] protocolsToSelectFrom = sslSocket.getEnabledProtocols();
    String[] protocolsToEnable = Util.intersect(String.class, tlsVersions, protocolsToSelectFrom);
    return new Builder(this)
        .cipherSuites(cipherSuitesToEnable)
        .tlsVersions(protocolsToEnable)
        .build();
  }

  /**
   * Returns {@code true} if the socket, as currently configured, supports this ConnectionSpec.
   * In order for a socket to be compatible the enabled cipher suites and protocols must match.
   *
   * <p>For cipher suites, at least one of the {@link #cipherSuites() required cipher suites} must
   * match the socket's enabled cipher suites. If there are no required cipher suites the socket
   * must have at least one cipher suite enable.
   *
   * <p>For protocols, the first {@link #tlsVersions() TLS version} is considered a minimum
   * requirement and must be be present in the socket's enabled protocols. For example, a
   * connection spec containing [TLSv1.2, TLSv1.1, etc.] is only compatible if the socket has
   * TLSv1.2 enabled.
   */
  public boolean isCompatible(SSLSocket socket) {
    if (!tls) {
      return false;
    }

    // We use enabled protocols here, not supported, to avoid re-enabling a protocol that has
    // been disabled. Just because something is supported does not make it desirable to use.
    String[] enabledProtocols = socket.getEnabledProtocols();
    // The protocols must contain the leading protocol from the connection spec to be considered
    // compatible. e.g. a connection spec containing [TLSv1.2, TLSv1.1, etc.] is only compatible
    // if the socket has TLSv1.2 enabled.
    String requiredProtocol = tlsVersions[0];
    boolean requiredProtocolEnabled = contains(enabledProtocols, requiredProtocol);

    boolean requiredCiphersEnabled;
    if (cipherSuites == null) {
      requiredCiphersEnabled = socket.getEnabledCipherSuites().length > 0;
    } else {
      String[] enabledCipherSuites = socket.getEnabledCipherSuites();
      requiredCiphersEnabled = false;
      for (String requiredCipher : cipherSuites) {
        if (contains(enabledCipherSuites, requiredCipher)) {
          requiredCiphersEnabled = true;
          break;
        }
      }
    }
    return requiredProtocolEnabled && requiredCiphersEnabled;
  }

  private static <T> boolean contains(T[] array, T value) {
    for (T arrayValue : array) {
      if (value == arrayValue || (value != null && value.equals(arrayValue))) {
        return true;
      }
    }
    return false;
  }

  @Override public boolean equals(Object other) {
    if (!(other instanceof ConnectionSpec)) return false;

    ConnectionSpec that = (ConnectionSpec) other;
    if (this.tls != that.tls) return false;

    if (tls) {
      if (!Arrays.equals(this.cipherSuites, that.cipherSuites)) return false;
      if (!Arrays.equals(this.tlsVersions, that.tlsVersions)) return false;
      if (this.supportsTlsExtensions != that.supportsTlsExtensions) return false;
    }

    return true;
  }

  @Override public int hashCode() {
    int result = 17;
    if (tls) {
      result = 31 * result + Arrays.hashCode(cipherSuites);
      result = 31 * result + Arrays.hashCode(tlsVersions);
      result = 31 * result + (supportsTlsExtensions ? 0 : 1);
    }
    return result;
  }

  @Override public String toString() {
    if (tls) {
      List<CipherSuite> cipherSuites = cipherSuites();
      String cipherSuitesString = cipherSuites == null ? "[use default]" : cipherSuites.toString();
      return "ConnectionSpec(cipherSuites=" + cipherSuitesString
          + ", tlsVersions=" + tlsVersions()
          + ", supportsTlsExtensions=" + supportsTlsExtensions
          + ")";
    } else {
      return "ConnectionSpec()";
    }
  }

  public static final class Builder {
    private boolean tls;
    private String[] cipherSuites;
    private String[] tlsVersions;
    private boolean supportsTlsExtensions;

    Builder(boolean tls) {
      this.tls = tls;
    }

    public Builder(ConnectionSpec connectionSpec) {
      this.tls = connectionSpec.tls;
      this.cipherSuites = connectionSpec.cipherSuites;
      this.tlsVersions = connectionSpec.tlsVersions;
      this.supportsTlsExtensions = connectionSpec.supportsTlsExtensions;
    }

    public Builder cipherSuites(CipherSuite... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");

      // Convert enums to the string names Java wants. This makes a defensive copy!
      String[] strings = new String[cipherSuites.length];
      for (int i = 0; i < cipherSuites.length; i++) {
        strings[i] = cipherSuites[i].javaName;
      }

      return cipherSuites(strings);
    }

    Builder cipherSuites(String[] cipherSuites) {
      this.cipherSuites = cipherSuites; // No defensive copy.
      return this;
    }

    /**
     * Sets the TLS versions to use. Ordering is important. See
     * {@link #isCompatible(javax.net.ssl.SSLSocket)}.
     */
    public Builder tlsVersions(TlsVersion... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");
      if (tlsVersions.length == 0) {
        throw new IllegalArgumentException("At least one TlsVersion is required");
      }

      // Convert enums to the string names Java wants. This makes a defensive copy!
      String[] strings = new String[tlsVersions.length];
      for (int i = 0; i < tlsVersions.length; i++) {
        strings[i] = tlsVersions[i].javaName;
      }

      return tlsVersions(strings);
    }

    Builder tlsVersions(String... tlsVersions) {
      this.tlsVersions = tlsVersions; // No defensive copy.
      return this;
    }

    public Builder supportsTlsExtensions(boolean supportsTlsExtensions) {
      if (!tls) throw new IllegalStateException("no TLS extensions for cleartext connections");
      this.supportsTlsExtensions = supportsTlsExtensions;
      return this;
    }

    public ConnectionSpec build() {
      return new ConnectionSpec(this);
    }
  }
}
