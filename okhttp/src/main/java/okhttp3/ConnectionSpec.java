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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLSocket;

import static okhttp3.internal.Util.concat;
import static okhttp3.internal.Util.indexOf;
import static okhttp3.internal.Util.intersect;

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
 */
public final class ConnectionSpec {

  // This is nearly equal to the cipher suites supported in Chrome 51, current as of 2016-05-25.
  // All of these suites are available on Android 7.0; earlier releases support a subset of these
  // suites. https://github.com/square/okhttp/issues/1972
  private static final CipherSuite[] APPROVED_CIPHER_SUITES = new CipherSuite[] {
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,

      // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
      // continue to include them until better suites are commonly available. For example, none
      // of the better cipher suites listed above shipped with Android 4.4 or Java 7.
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
      CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
  };

  /** A modern TLS connection with extensions like SNI and ALPN available. */
  public static final ConnectionSpec MODERN_TLS = new Builder(true)
      .cipherSuites(APPROVED_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  /** A backwards-compatible fallback connection for interop with obsolete servers. */
  public static final ConnectionSpec COMPATIBLE_TLS = new Builder(MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_0)
      .supportsTlsExtensions(true)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final ConnectionSpec CLEARTEXT = new Builder(false).build();

  private final boolean tls;
  private final boolean supportsTlsExtensions;
  private final String[] cipherSuites;
  private final String[] tlsVersions;

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
   * Returns the cipher suites to use for a connection. Returns {@code null} if all of the SSL
   * socket's enabled cipher suites should be used.
   */
  public List<CipherSuite> cipherSuites() {
    if (cipherSuites == null) return null;

    List<CipherSuite> result = new ArrayList<>(cipherSuites.length);
    for (String cipherSuite : cipherSuites) {
      result.add(CipherSuite.forJavaName(cipherSuite));
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Returns the TLS versions to use when negotiating a connection. Returns {@code null} if all of
   * the SSL socket's enabled TLS versions should be used.
   */
  public List<TlsVersion> tlsVersions() {
    if (tlsVersions == null) return null;

    List<TlsVersion> result = new ArrayList<>(tlsVersions.length);
    for (String tlsVersion : tlsVersions) {
      result.add(TlsVersion.forJavaName(tlsVersion));
    }
    return Collections.unmodifiableList(result);
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
        ? intersect(String.class, cipherSuites, sslSocket.getEnabledCipherSuites())
        : sslSocket.getEnabledCipherSuites();
    String[] tlsVersionsIntersection = tlsVersions != null
        ? intersect(String.class, tlsVersions, sslSocket.getEnabledProtocols())
        : sslSocket.getEnabledProtocols();

    // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
    // the SCSV cipher is added to signal that a protocol fallback has taken place.
    if (isFallback && indexOf(sslSocket.getSupportedCipherSuites(), "TLS_FALLBACK_SCSV") != -1) {
      cipherSuitesIntersection = concat(cipherSuitesIntersection, "TLS_FALLBACK_SCSV");
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

    if (tlsVersions != null
        && !nonEmptyIntersection(tlsVersions, socket.getEnabledProtocols())) {
      return false;
    }

    if (cipherSuites != null
        && !nonEmptyIntersection(cipherSuites, socket.getEnabledCipherSuites())) {
      return false;
    }

    return true;
  }

  /**
   * An N*M intersection that terminates if any intersection is found. The sizes of both arguments
   * are assumed to be so small, and the likelihood of an intersection so great, that it is not
   * worth the CPU cost of sorting or the memory cost of hashing.
   */
  private static boolean nonEmptyIntersection(String[] a, String[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0) {
      return false;
    }
    for (String toFind : a) {
      if (indexOf(b, toFind) != -1) {
        return true;
      }
    }
    return false;
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
    if (!tls) {
      return "ConnectionSpec()";
    }

    String cipherSuitesString = cipherSuites != null ? cipherSuites().toString() : "[all enabled]";
    String tlsVersionsString = tlsVersions != null ? tlsVersions().toString() : "[all enabled]";
    return "ConnectionSpec("
        + "cipherSuites=" + cipherSuitesString
        + ", tlsVersions=" + tlsVersionsString
        + ", supportsTlsExtensions=" + supportsTlsExtensions
        + ")";
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

    public Builder allEnabledCipherSuites() {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");
      this.cipherSuites = null;
      return this;
    }

    public Builder cipherSuites(CipherSuite... cipherSuites) {
      if (!tls) throw new IllegalStateException("no cipher suites for cleartext connections");

      String[] strings = new String[cipherSuites.length];
      for (int i = 0; i < cipherSuites.length; i++) {
        strings[i] = cipherSuites[i].javaName;
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
        strings[i] = tlsVersions[i].javaName;
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
