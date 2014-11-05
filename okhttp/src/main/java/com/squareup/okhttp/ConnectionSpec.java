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
  private static final String TLS_1_2 = "TLSv1.2"; // 2008.
  private static final String TLS_1_1 = "TLSv1.1"; // 2006.
  private static final String TLS_1_0 = "TLSv1";   // 1999.
  private static final String SSL_3_0 = "SSLv3";   // 1996.

  /** A modern TLS connection with extensions like SNI and ALPN available. */
  public static final ConnectionSpec MODERN_TLS = new Builder(true)
      .cipherSuites(
          // This is a subset of the cipher suites supported in Chrome 37, current as of 2014-10-5.
          // All of these suites are available on Android L; earlier releases support a subset of
          // these suites. https://github.com/square/okhttp/issues/330
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", // 0xC0,0x2B  Android L
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",   // 0xC0,0x2F  Android L
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",     // 0x00,0x9E  Android L
          "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",    // 0xC0,0x0A  Android 4.0
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",    // 0xC0,0x09  Android 4.0
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",      // 0xC0,0x13  Android 4.0
          "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",      // 0xC0,0x14  Android 4.0
          "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",        // 0xC0,0x07  Android 4.0
          "TLS_ECDHE_RSA_WITH_RC4_128_SHA",          // 0xC0,0x11  Android 4.0
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",        // 0x00,0x33  Android 2.3
          "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",        // 0x00,0x32  Android 2.3
          "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",        // 0x00,0x39  Android 2.3
          "TLS_RSA_WITH_AES_128_GCM_SHA256",         // 0x00,0x9C  Android L
          "TLS_RSA_WITH_AES_128_CBC_SHA",            // 0x00,0x2F  Android 2.3
          "TLS_RSA_WITH_AES_256_CBC_SHA",            // 0x00,0x35  Android 2.3
          "SSL_RSA_WITH_3DES_EDE_CBC_SHA",           // 0x00,0x0A  Android 2.3  (Deprecated in L)
          "SSL_RSA_WITH_RC4_128_SHA",                // 0x00,0x05  Android 2.3
          "SSL_RSA_WITH_RC4_128_MD5"                 // 0x00,0x04  Android 2.3  (Deprecated in L)
      )
      .tlsVersions(TLS_1_2, TLS_1_1, TLS_1_0, SSL_3_0)
      .supportsTlsExtensions(true)
      .build();

  /** A backwards-compatible fallback connection for interop with obsolete servers. */
  public static final ConnectionSpec COMPATIBLE_TLS = new Builder(MODERN_TLS)
      .tlsVersions(SSL_3_0)
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

  public List<String> cipherSuites() {
    return Util.immutableList(cipherSuites);
  }

  public List<String> tlsVersions() {
    return Util.immutableList(tlsVersions);
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
    sslSocket.setEnabledCipherSuites(specToApply.cipherSuites);

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
    List<String> supportedCipherSuites = Util.intersect(Arrays.asList(cipherSuites),
        Arrays.asList(sslSocket.getSupportedCipherSuites()));
    List<String> supportedTlsVersions = Util.intersect(Arrays.asList(tlsVersions),
        Arrays.asList(sslSocket.getSupportedProtocols()));
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
      return "ConnectionSpec(cipherSuites=" + Arrays.toString(cipherSuites)
          + ", tlsVersions=" + Arrays.toString(tlsVersions)
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

    public Builder cipherSuites(String... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");
      this.cipherSuites = cipherSuites.clone(); // Defensive copy.
      return this;
    }

    public Builder tlsVersions(String... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");
      this.tlsVersions = tlsVersions.clone(); // Defensive copy.
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
