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
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.equal;

/**
 * Configuration for the socket connection that HTTP traffic travels through.
 * For {@code https:} URLs, this includes the TLS version and ciphers to use
 * when negotiating a secure connection.
 */
public final class ConnectionConfiguration {
  private static final List<Protocol> DEFAULT_PROTOCOLS = Util.immutableList(
      Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1);

  private static final String TLS_1_2 = "TLSv1.2"; // 2008.
  private static final String TLS_1_1 = "TLSv1.1"; // 2006.
  private static final String TLS_1_0 = "TLSv1";   // 1999.
  private static final String SSL_3_0 = "SSLv3";   // 1996.

  /** Lazily-initialized. */
  private static SSLSocketFactory defaultSslSocketFactory;

  /** A modern TLS configuration with extensions like SNI and ALPN available. */
  public static final ConnectionConfiguration MODERN_TLS = new Builder(true)
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

  /** A backwards-compatible fallback configuration for interop with obsolete servers. */
  public static final ConnectionConfiguration COMPATIBLE_TLS = new Builder(MODERN_TLS)
      .tlsVersions(SSL_3_0)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final ConnectionConfiguration CLEARTEXT = new Builder(false).build();

  final SocketFactory socketFactory;
  final Authenticator authenticator;
  final boolean tls;
  private final String[] cipherSuites;
  private final String[] tlsVersions;
  final boolean supportsTlsExtensions;
  final SSLSocketFactory sslSocketFactory;
  final HostnameVerifier hostnameVerifier;
  final CertificatePinner certificatePinner;
  final List<Protocol> protocols;

  /**
   * Caches the subset of this configuration that's supported by the host
   * platform. It's possible that the platform hosts multiple implementations of
   * {@link SSLSocket}, in which case this cache will be incorrect.
   */
  private ConnectionConfiguration supportedConfiguration;

  private ConnectionConfiguration(Builder builder) {
    this.tls = builder.tls;
    this.cipherSuites = builder.cipherSuites;
    this.tlsVersions = builder.tlsVersions;
    this.supportsTlsExtensions = builder.supportsTlsExtensions;
    this.socketFactory = builder.socketFactory == null
        ? SocketFactory.getDefault()
        : builder.socketFactory;
    this.authenticator = builder.authenticator == null
        ? AuthenticatorAdapter.INSTANCE
        : builder.authenticator;
    this.sslSocketFactory = builder.tls && builder.sslSocketFactory == null
        ? getDefaultSSLSocketFactory()
        : builder.sslSocketFactory;
    this.hostnameVerifier = builder.tls && builder.hostnameVerifier == null
        ? OkHostnameVerifier.INSTANCE
        : builder.hostnameVerifier;
    this.certificatePinner = builder.tls && builder.certificatePinner == null
        ? CertificatePinner.DEFAULT
        : builder.certificatePinner;
    this.protocols = builder.tls && builder.protocols == null
        ? DEFAULT_PROTOCOLS
        : builder.protocols;
  }

  public Authenticator authenticator() {
    return authenticator;
  }

  public SocketFactory socketFactory() {
    return socketFactory;
  }

  public SSLSocketFactory sslSocketFactory() {
    return sslSocketFactory;
  }

  public HostnameVerifier hostnameVerifier() {
    return hostnameVerifier;
  }

  public CertificatePinner certificatePinner() {
    return certificatePinner;
  }

  public List<Protocol> protocols() {
    return protocols;
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

  /** Applies this configuration to {@code sslSocket} for {@code route}. */
  public void apply(SSLSocket sslSocket, Route route) {
    ConnectionConfiguration configurationToApply = supportedConfiguration;
    if (configurationToApply == null) {
      configurationToApply = supportedConfiguration(sslSocket);
      supportedConfiguration = configurationToApply;
    }

    sslSocket.setEnabledProtocols(configurationToApply.tlsVersions);
    sslSocket.setEnabledCipherSuites(configurationToApply.cipherSuites);

    Platform platform = Platform.get();
    if (configurationToApply.supportsTlsExtensions) {
      platform.configureTlsExtensions(sslSocket, route.address.uriHost, protocols);
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not
   * supported by {@code sslSocket}.
   */
  private ConnectionConfiguration supportedConfiguration(SSLSocket sslSocket) {
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
    if (!(other instanceof ConnectionConfiguration)) return false;

    ConnectionConfiguration that = (ConnectionConfiguration) other;
    if (!equal(this.socketFactory, that.socketFactory)) return false;
    if (!equal(this.authenticator, that.authenticator)) return false;
    if (this.tls != that.tls) return false;

    if (tls) {
      if (!Arrays.equals(this.cipherSuites, that.cipherSuites)) return false;
      if (!Arrays.equals(this.tlsVersions, that.tlsVersions)) return false;
      if (this.supportsTlsExtensions != that.supportsTlsExtensions) return false;
      if (!equal(this.sslSocketFactory, that.sslSocketFactory)) return false;
      if (!equal(this.hostnameVerifier, that.hostnameVerifier)) return false;
      if (!equal(this.certificatePinner, that.certificatePinner)) return false;
      if (!equal(this.protocols, that.protocols)) return false;
    }

    return true;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + socketFactory.hashCode();
    result = 31 * result + authenticator.hashCode();
    if (tls) {
      result = 31 * result + Arrays.hashCode(cipherSuites);
      result = 31 * result + Arrays.hashCode(tlsVersions);
      result = 31 * result + (supportsTlsExtensions ? 0 : 1);
      result = 31 * result + sslSocketFactory.hashCode();
      result = 31 * result + hostnameVerifier.hashCode();
      result = 31 * result + certificatePinner.hashCode();
      result = 31 * result + protocols.hashCode();
    }
    return result;
  }

  @Override public String toString() {
    StringBuilder result = new StringBuilder()
        .append("ConnectionConfiguration(socketFactory=").append(socketFactory)
        .append(", authenticator=").append(authenticator);
    if (tls) {
      result.append(", cipherSuites=").append(Arrays.toString(cipherSuites))
          .append(", tlsVersions=").append(Arrays.toString(tlsVersions))
          .append(", supportsTlsExtensions=").append(supportsTlsExtensions)
          .append(", sslSocketFactory=").append(sslSocketFactory)
          .append(", hostnameVerifier=").append(hostnameVerifier)
          .append(", certificatePinner=").append(certificatePinner)
          .append(", protocols=").append(protocols);
    }
    return result.append(")").toString();
  }

  public static final class Builder {
    private SocketFactory socketFactory;
    private Authenticator authenticator;
    private boolean tls;
    private String[] cipherSuites;
    private String[] tlsVersions;
    private boolean supportsTlsExtensions;
    private SSLSocketFactory sslSocketFactory;
    private HostnameVerifier hostnameVerifier;
    private CertificatePinner certificatePinner;
    private List<Protocol> protocols;

    private Builder(boolean tls) {
      this.tls = tls;
    }

    public Builder(ConnectionConfiguration connectionConfiguration) {
      this.socketFactory = connectionConfiguration.socketFactory;
      this.authenticator = connectionConfiguration.authenticator;
      this.tls = connectionConfiguration.tls;
      this.cipherSuites = connectionConfiguration.cipherSuites;
      this.tlsVersions = connectionConfiguration.tlsVersions;
      this.supportsTlsExtensions = connectionConfiguration.supportsTlsExtensions;
      this.sslSocketFactory = connectionConfiguration.sslSocketFactory;
      this.hostnameVerifier = connectionConfiguration.hostnameVerifier;
      this.certificatePinner = connectionConfiguration.certificatePinner;
      this.protocols = connectionConfiguration.protocols;
    }

    public Builder socketFactory(SocketFactory socketFactory) {
      this.socketFactory = socketFactory;
      return this;
    }

    public Builder authenticator(Authenticator authenticator) {
      if (authenticator == null) throw new IllegalArgumentException("authenticator == null");
      this.authenticator = authenticator;
      return this;
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

    public Builder sslSocketFactory(SSLSocketFactory sslSocketFactory) {
      if (!tls) throw new IllegalStateException("no SSL sockets for cleartext connections");
      this.sslSocketFactory = sslSocketFactory;
      return this;
    }

    public Builder hostnameVerifier(HostnameVerifier hostnameVerifier) {
      if (!tls) throw new IllegalStateException("no hostname verifier for cleartext connections");
      this.hostnameVerifier = hostnameVerifier;
      return this;
    }

    public Builder certificatePinner(CertificatePinner certificatePinner) {
      if (!tls) throw new IllegalStateException("no certificate pinner for cleartext connections");
      this.certificatePinner = certificatePinner;
      return this;
    }

    public Builder protocols(List<Protocol> protocols) {
      if (!tls) throw new IllegalStateException("no protocols for cleartext connections");
      if (protocols == null) throw new IllegalArgumentException("protocols == null");
      this.protocols = Util.immutableList(protocols);
      return this;
    }

    public ConnectionConfiguration build() {
      return new ConnectionConfiguration(this);
    }
  }

  /**
   * Java and Android programs default to using a single global SSL context,
   * accessible to HTTP clients as {@link SSLSocketFactory#getDefault()}. If we
   * used the shared SSL context, when OkHttp enables NPN for its SPDY-related
   * stuff, it would also enable NPN for other usages, which might crash them
   * because NPN is enabled when it isn't expected to be.
   *
   * <p>This code avoids that by defaulting to an OkHttp-created SSL context.
   * The drawback of this approach is that apps that customize the global SSL
   * context will lose these customizations.
   */
  private static synchronized SSLSocketFactory getDefaultSSLSocketFactory() {
    if (defaultSslSocketFactory == null) {
      try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        defaultSslSocketFactory = sslContext.getSocketFactory();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(); // The system has no TLS. Just give up.
      }
    }
    return defaultSslSocketFactory;
  }
}
