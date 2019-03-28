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
package okhttp3;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSocket;
import okhttp3.internal.Util;

import static okhttp3.internal.InternalKtKt.CIPHER_SUITE_ORDER_BY_NAME;
import static okhttp3.internal.InternalKtKt.cipherSuitesForJavaNames;
import static okhttp3.internal.Util.concat;
import static okhttp3.internal.Util.indexOf;
import static okhttp3.internal.Util.intersect;
import static okhttp3.internal.Util.nonEmptyIntersection;
import static okhttp3.internal.Util.tlsVersionsForJavaNames;

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For {@code
 * https:} URLs, this includes the TLS version and cipher suites to use when negotiating a secure
 * connection.
 *
 * <p>The TLS versions configured in a connection spec are only be used if they are also enabled in
 * the SSL socket. For example, if an SSL socket does not have TLS 1.3 enabled, it will not be used
 * even if it is present on the connection spec. The same policy also applies to cipher suites.
 *
 * <p>Use {@link Builder#allEnabledTlsVersions()} and {@link Builder#allEnabledCipherSuites} to
 * defer all feature selection to the underlying SSL socket.
 *
 * <p>The configuration of each spec changes with each OkHttp release. This is annoying: upgrading
 * your OkHttp library can break connectivity to certain web servers! But it’s a necessary annoyance
 * because the TLS ecosystem is dynamic and staying up to date is necessary to stay secure. See
 * <a href="https://github.com/square/okhttp/wiki/TLS-Configuration-History">OkHttp's TLS
 * Configuration History</a> to track these changes.
 */
public final class ConnectionSpec {

  // Most secure but generally supported list.
  private static final CipherSuite[] RESTRICTED_CIPHER_SUITES = new CipherSuite[] {
      // TLSv1.3.
      CipherSuite.TLS_AES_128_GCM_SHA256,
      CipherSuite.TLS_AES_256_GCM_SHA384,
      CipherSuite.TLS_CHACHA20_POLY1305_SHA256,

      // TLSv1.0, TLSv1.1, TLSv1.2.
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
  };

  // This is nearly equal to the cipher suites supported in Chrome 72, current as of 2019-02-24.
  // See https://tinyurl.com/okhttp-cipher-suites for availability.
  private static final CipherSuite[] APPROVED_CIPHER_SUITES = new CipherSuite[] {
      // TLSv1.3.
      CipherSuite.TLS_AES_128_GCM_SHA256,
      CipherSuite.TLS_AES_256_GCM_SHA384,
      CipherSuite.TLS_CHACHA20_POLY1305_SHA256,

      // TLSv1.0, TLSv1.1, TLSv1.2.
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,

      // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
      // continue to include them until better suites are commonly available.
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
  };

  /** A secure TLS connection that requires a recent client platform and a recent server. */
  public static final ConnectionSpec RESTRICTED_TLS = new Builder(true)
      .cipherSuites(RESTRICTED_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
      .supportsTlsExtensions(true)
      .build();

  /**
   * A modern TLS configuration that works on most client platforms and can connect to most servers.
   * This is OkHttp's default configuration.
   */
  public static final ConnectionSpec MODERN_TLS = new Builder(true)
      .cipherSuites(APPROVED_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
      .supportsTlsExtensions(true)
      .build();

  /**
   * A backwards-compatible fallback configuration that works on obsolete client platforms and can
   * connect to obsolete servers. When possible, prefer to upgrade your client platform or server
   * rather than using this configuration.
   */
  public static final ConnectionSpec COMPATIBLE_TLS = new Builder(true)
      .cipherSuites(APPROVED_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final ConnectionSpec CLEARTEXT = new Builder(false).build();

  final boolean tls;
  final boolean supportsTlsExtensions;
  final @Nullable String[] cipherSuites;
  final @Nullable String[] tlsVersions;

  ConnectionSpec(Builder builder) {
    this.tls = builder.tls;
    this.cipherSuites = builder.cipherSuites;
    this.tlsVersions = builder.tlsVersions;
    this.supportsTlsExtensions = builder.supportsTlsExtensions;
  }

  public boolean isTls() {
    return tls;
  }

  /**
   * Returns the cipher suites to use for a connection. Returns null if all of the SSL socket's
   * enabled cipher suites should be used.
   */
  public @Nullable List<CipherSuite> cipherSuites() {
    return cipherSuites != null ? cipherSuitesForJavaNames(cipherSuites) : null;
  }

  /**
   * Returns the TLS versions to use when negotiating a connection. Returns null if all of the SSL
   * socket's enabled TLS versions should be used.
   */
  public @Nullable List<TlsVersion> tlsVersions() {
    return tlsVersions != null ? tlsVersionsForJavaNames(tlsVersions) : null;
  }

  public boolean supportsTlsExtensions() {
    return supportsTlsExtensions;
  }

  /** Applies this spec to {@code sslSocket}. */
  void apply(SSLSocket sslSocket, boolean isFallback) {
    ConnectionSpec specToApply = supportedSpec(sslSocket, isFallback);

    if (specToApply.tlsVersions != null) {
      sslSocket.setEnabledProtocols(specToApply.tlsVersions);
    }
    if (specToApply.cipherSuites != null) {
      sslSocket.setEnabledCipherSuites(specToApply.cipherSuites);
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not enabled by {@code
   * sslSocket}.
   */
  private ConnectionSpec supportedSpec(SSLSocket sslSocket, boolean isFallback) {
    String[] cipherSuitesIntersection = cipherSuites != null
        ? intersect(CIPHER_SUITE_ORDER_BY_NAME, sslSocket.getEnabledCipherSuites(), cipherSuites)
        : sslSocket.getEnabledCipherSuites();
    String[] tlsVersionsIntersection = tlsVersions != null
        ? intersect(Util.NATURAL_ORDER, sslSocket.getEnabledProtocols(), tlsVersions)
        : sslSocket.getEnabledProtocols();

    // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
    // the SCSV cipher is added to signal that a protocol fallback has taken place.
    String[] supportedCipherSuites = sslSocket.getSupportedCipherSuites();
    int indexOfFallbackScsv = indexOf(
        CIPHER_SUITE_ORDER_BY_NAME, supportedCipherSuites, "TLS_FALLBACK_SCSV");
    if (isFallback && indexOfFallbackScsv != -1) {
      cipherSuitesIntersection = concat(
          cipherSuitesIntersection, supportedCipherSuites[indexOfFallbackScsv]);
    }

    return new Builder(this)
        .cipherSuites(cipherSuitesIntersection)
        .tlsVersions(tlsVersionsIntersection)
        .build();
  }

  /**
   * Returns {@code true} if the socket, as currently configured, supports this connection spec. In
   * order for a socket to be compatible the enabled cipher suites and protocols must intersect.
   *
   * <p>For cipher suites, at least one of the {@link #cipherSuites() required cipher suites} must
   * match the socket's enabled cipher suites. If there are no required cipher suites the socket
   * must have at least one cipher suite enabled.
   *
   * <p>For protocols, at least one of the {@link #tlsVersions() required protocols} must match the
   * socket's enabled protocols.
   */
  public boolean isCompatible(SSLSocket socket) {
    if (!tls) {
      return false;
    }

    if (tlsVersions != null && !nonEmptyIntersection(
        Util.NATURAL_ORDER, tlsVersions, socket.getEnabledProtocols())) {
      return false;
    }

    if (cipherSuites != null && !nonEmptyIntersection(
        CIPHER_SUITE_ORDER_BY_NAME, cipherSuites, socket.getEnabledCipherSuites())) {
      return false;
    }

    return true;
  }

  @Override public boolean equals(@Nullable Object other) {
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
    if (!tls) {
      return "ConnectionSpec()";
    }

    return "ConnectionSpec("
        + "cipherSuites=" + Objects.toString(cipherSuites(), "[all enabled]")
        + ", tlsVersions=" + Objects.toString(tlsVersions(), "[all enabled]")
        + ", supportsTlsExtensions=" + supportsTlsExtensions
        + ")";
  }

  public static final class Builder {
    boolean tls;
    @Nullable String[] cipherSuites;
    @Nullable String[] tlsVersions;
    boolean supportsTlsExtensions;

    Builder(boolean tls) {
      this.tls = tls;
    }

    public Builder(ConnectionSpec connectionSpec) {
      this.tls = connectionSpec.tls;
      this.cipherSuites = connectionSpec.cipherSuites;
      this.tlsVersions = connectionSpec.tlsVersions;
      this.supportsTlsExtensions = connectionSpec.supportsTlsExtensions;
    }

    public Builder allEnabledCipherSuites() {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");
      this.cipherSuites = null;
      return this;
    }

    public Builder cipherSuites(CipherSuite... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");

      String[] strings = new String[cipherSuites.length];
      for (int i = 0; i < cipherSuites.length; i++) {
        strings[i] = cipherSuites[i].javaName();
      }
      return cipherSuites(strings);
    }

    public Builder cipherSuites(String... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");

      if (cipherSuites.length == 0) {
        throw new IllegalArgumentException("At least one cipher suite is required");
      }

      this.cipherSuites = cipherSuites.clone(); // Defensive copy.
      return this;
    }

    public Builder allEnabledTlsVersions() {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");
      this.tlsVersions = null;
      return this;
    }

    public Builder tlsVersions(TlsVersion... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");

      String[] strings = new String[tlsVersions.length];
      for (int i = 0; i < tlsVersions.length; i++) {
        strings[i] = tlsVersions[i].javaName();
      }

      return tlsVersions(strings);
    }

    public Builder tlsVersions(String... tlsVersions) {
      if (!tls) throw new IllegalStateException("no TLS versions for cleartext connections");

      if (tlsVersions.length == 0) {
        throw new IllegalArgumentException("At least one TLS version is required");
      }

      this.tlsVersions = tlsVersions.clone(); // Defensive copy.
      return this;
    }

    /**
     * @deprecated since OkHttp 3.13 all TLS-connections are expected to support TLS extensions.
     *     In a future release setting this to true will be unnecessary and setting it to false will
     *     have no effect.
     */
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
