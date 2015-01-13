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

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For {@code
 * https:} URLs, this includes the TLS version and ciphers to use when negotiating a secure
 * connection.
 */
public final class ConnectionSpec {

  /** A modern TLS connection with extensions like SNI and ALPN available. */
  public static final ConnectionSpec MODERN_TLS = new Builder(true)
      .cipherSuites(
          // This is a subset of the cipher suites supported in Chrome 37, current as of 2014-10-5.
          // All of these suites are available on Android L; earlier releases support a subset of
          // these suites. https://github.com/square/okhttp/issues/330
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
          CipherSuite.TLS_RSA_WITH_RC4_128_MD5
      )
      .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  /** A backwards-compatible fallback connection for interop with obsolete servers. */
  public static final ConnectionSpec COMPATIBLE_TLS = new Builder(MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final ConnectionSpec CLEARTEXT = new Builder(false).build();

  final boolean tls;
  private final String[] cipherSuites;
  private final String[] tlsVersions;
  final boolean supportsTlsExtensions;

  /**
   * Caches the subset of this spec that's supported by the host platform. It's possible that the
   * platform hosts multiple implementations of {@link SSLSocket}, in which case this cache will be
   * incorrect.
   */
  private ConnectionSpec supportedSpec;

  private ConnectionSpec(Builder builder) {
    this.tls = builder.tls;
    this.cipherSuites = builder.cipherSuites;
    this.tlsVersions = builder.tlsVersions;
    this.supportsTlsExtensions = builder.supportsTlsExtensions;
  }

  public boolean isTls() {
    return tls;
  }

  public List<CipherSuite> cipherSuites() {
    CipherSuite[] result = new CipherSuite[cipherSuites.length];
    for (int i = 0; i < cipherSuites.length; i++) {
      result[i] = CipherSuite.forJavaName(cipherSuites[i]);
    }
    return Util.immutableList(result);
  }

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

  /** Applies this spec to {@code sslSocket} for {@code route}. */
  void apply(SSLSocket sslSocket, Route route) {
    ConnectionSpec specToApply = supportedSpec;
    if (specToApply == null) {
      specToApply = supportedSpec(sslSocket);
      supportedSpec = specToApply;
    }

    sslSocket.setEnabledProtocols(specToApply.tlsVersions);

    String[] cipherSuitesToEnable = specToApply.cipherSuites;
    if (route.shouldSendTlsFallbackIndicator) {
      // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
      // the SCSV cipher is added to signal that a protocol fallback has taken place.
      final String fallbackScsv = "TLS_FALLBACK_SCSV";
      boolean socketSupportsFallbackScsv =
          Arrays.asList(sslSocket.getSupportedCipherSuites()).contains(fallbackScsv);

      if (socketSupportsFallbackScsv) {
        // Add the SCSV cipher to the set of enabled ciphers iff it is supported.
        String[] oldEnabledCipherSuites = cipherSuitesToEnable;
        String[] newEnabledCipherSuites = new String[oldEnabledCipherSuites.length + 1];
        System.arraycopy(oldEnabledCipherSuites, 0,
            newEnabledCipherSuites, 0, oldEnabledCipherSuites.length);
        newEnabledCipherSuites[newEnabledCipherSuites.length - 1] = fallbackScsv;
        cipherSuitesToEnable = newEnabledCipherSuites;
      }
    }
    sslSocket.setEnabledCipherSuites(cipherSuitesToEnable);

    Platform platform = Platform.get();
    if (specToApply.supportsTlsExtensions) {
      platform.configureTlsExtensions(sslSocket, route.address.uriHost, route.address.protocols);
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not
   * supported by {@code sslSocket}.
   */
  private ConnectionSpec supportedSpec(SSLSocket sslSocket) {
    List<String> supportedCipherSuites =
        Util.intersect(cipherSuites, sslSocket.getSupportedCipherSuites());
    List<String> supportedTlsVersions =
        Util.intersect(tlsVersions, sslSocket.getSupportedProtocols());
    return new Builder(this)
        .cipherSuites(supportedCipherSuites.toArray(new String[supportedCipherSuites.size()]))
        .tlsVersions(supportedTlsVersions.toArray(new String[supportedTlsVersions.size()]))
        .build();
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
      return "ConnectionSpec(cipherSuites=" + cipherSuites()
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

    private Builder(boolean tls) {
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

    public Builder tlsVersions(TlsVersion... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");

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
