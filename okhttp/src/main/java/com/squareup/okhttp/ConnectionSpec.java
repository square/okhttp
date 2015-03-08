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
          // All of these suites are available on Android 5.0; earlier releases support a subset of
          // these suites. https://github.com/square/okhttp/issues/330
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,

          // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
          // continue to include them until better suites are commonly available. For example, none
          // of the better cipher suites listed above shipped with Android 4.4 or Java 7.
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
          CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
          CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
          CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
          CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
          CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
          CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA
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
   * Return the cipher suites to use with the connection. This method can return {@code null} if the
   * ciphers enabled by default should be used.
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
    ConnectionSpec specToApply = supportedSpec(sslSocket);

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
    // null means "use default set".
    if (cipherSuitesToEnable != null) {
      sslSocket.setEnabledCipherSuites(cipherSuitesToEnable);
    }

    Platform platform = Platform.get();
    if (specToApply.supportsTlsExtensions) {
      platform.configureTlsExtensions(sslSocket, route.address.uriHost, route.address.protocols);
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not
   * enabled by {@code sslSocket}.
   */
  private ConnectionSpec supportedSpec(SSLSocket sslSocket) {
    String[] cipherSuitesToEnable = null;
    if (cipherSuites != null) {
      String[] cipherSuitesToSelectFrom = sslSocket.getEnabledCipherSuites();
      cipherSuitesToEnable =
          Util.intersect(String.class, cipherSuites, cipherSuitesToSelectFrom);
    }

    String[] protocolsToSelectFrom = sslSocket.getEnabledProtocols();
    String[] tlsVersionsToEnable = Util.intersect(String.class, tlsVersions, protocolsToSelectFrom);
    return new Builder(this)
        .cipherSuites(cipherSuitesToEnable)
        .tlsVersions(tlsVersionsToEnable)
        .build();
  }

  @Override public boolean equals(Object other) {
    if (!(other instanceof ConnectionSpec)) return false;
    if (other == this) return true;

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
      this.cipherSuites = strings;
      return this;
    }

    public Builder cipherSuites(String... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");

      if (cipherSuites == null) {
        this.cipherSuites = null;
      } else {
        // This makes a defensive copy!
        this.cipherSuites = cipherSuites.clone();
      }

      return this;
    }

    public Builder tlsVersions(TlsVersion... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");

      // Convert enums to the string names Java wants. This makes a defensive copy!
      String[] strings = new String[tlsVersions.length];
      for (int i = 0; i < tlsVersions.length; i++) {
        strings[i] = tlsVersions[i].javaName;
      }
      this.tlsVersions = strings;
      return this;
    }

    public Builder tlsVersions(String... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");

      if (tlsVersions == null) {
        this.tlsVersions = null;
      } else {
        // This makes a defensive copy!
        this.tlsVersions = tlsVersions.clone();
      }

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
