/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.tls;

import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.CertificatePinner;
import okhttp3.internal.Util;
import okhttp3.internal.platform.Platform;

import static okhttp3.tls.internal.TlsUtil.newKeyManager;
import static okhttp3.tls.internal.TlsUtil.newTrustManager;

/**
 * Certificates to identify which peers to trust and also to earn the trust of those peers in kind.
 * Client and server exchange these certificates during the handshake phase of a TLS connection.
 *
 * <h3>Server Authentication</h3>
 *
 * <p>This is the most common form of TLS authentication: clients verify that servers are trusted
 * and that they own the hostnames that they represent. Server authentication is required.
 *
 * <p>To perform server authentication:
 *
 * <ul>
 *   <li>The server's handshake certificates must have a {@linkplain HeldCertificate held
 *       certificate} (a certificate and its private key). The certificate's subject alternative
 *       names must match the server's hostname. The server must also have is a (possibly-empty)
 *       chain of intermediate certificates to establish trust from a root certificate to the
 *       server's certificate. The root certificate is not included in this chain.
 *   <li>The client's handshake certificates must include a set of trusted root certificates. They
 *       will be used to authenticate the server's certificate chain. Typically this is a set of
 *       well-known root certificates that is distributed with the HTTP client or its platform. It
 *       may be augmented by certificates private to an organization or service.
 * </ul>
 *
 * <h3>Client Authentication</h3>
 *
 * <p>This is authentication of the client by the server during the TLS handshake. Client
 * authentication is optional.
 *
 * <p>To perform client authentication:
 *
 * <ul>
 *   <li>The client's handshake certificates must have a {@linkplain HeldCertificate held
 *       certificate} (a certificate and its private key). The client must also have a
 *       (possibly-empty) chain of intermediate certificates to establish trust from a root
 *       certificate to the client's certificate. The root certificate is not included in this
 *       chain.
 *   <li>The server's handshake certificates must include a set of trusted root certificates. They
 *       will be used to authenticate the client's certificate chain. Typically this is not the same
 *       set of root certificates used in server authentication. Instead it will be a small set of
 *       roots private to an organization or service.
 * </ul>
 */
public final class HandshakeCertificates {
  private final X509KeyManager keyManager;
  private final X509TrustManager trustManager;

  private HandshakeCertificates(X509KeyManager keyManager, X509TrustManager trustManager) {
    this.keyManager = keyManager;
    this.trustManager = trustManager;
  }

  public X509KeyManager keyManager() {
    return keyManager;
  }

  public X509TrustManager trustManager() {
    return trustManager;
  }

  public SSLSocketFactory sslSocketFactory() {
    return sslContext().getSocketFactory();
  }

  public SSLContext sslContext() {
    try {
      SSLContext sslContext = Platform.get().getSSLContext();
      sslContext.init(new KeyManager[] { keyManager }, new TrustManager[] { trustManager },
          new SecureRandom());
      return sslContext;
    } catch (KeyManagementException e) {
      throw new AssertionError(e);
    }
  }

  public static final class Builder {
    private @Nullable HeldCertificate heldCertificate;
    private @Nullable X509Certificate[] intermediates;

    private final List<X509Certificate> trustedCertificates = new ArrayList<>();

    /**
     * Configure the certificate chain to use when being authenticated. The first certificate is
     * the held certificate, further certificates are included in the handshake so the peer can
     * build a trusted path to a trusted root certificate.
     *
     * <p>The chain should include all intermediate certificates but does not need the root
     * certificate that we expect to be known by the remote peer. The peer already has that
     * certificate so transmitting it is unnecessary.
     */
    public Builder heldCertificate(HeldCertificate heldCertificate,
        X509Certificate... intermediates) {
      this.heldCertificate = heldCertificate;
      this.intermediates = intermediates.clone(); // Defensive copy.
      return this;
    }

    /**
     * Add a trusted root certificate to use when authenticating a peer. Peers must provide
     * a chain of certificates whose root is one of these.
     */
    public Builder addTrustedCertificate(X509Certificate certificate) {
      this.trustedCertificates.add(certificate);
      return this;
    }

    /**
     * Add all of the host platform's trusted root certificates. This set varies by platform
     * (Android vs. Java), by platform release (Android 4.4 vs. Android 9), and with user
     * customizations.
     *
     * <p>Most TLS clients that connect to hosts on the public Internet should call this method.
     * Otherwise it is necessary to manually prepare a comprehensive set of trusted roots.
     *
     * <p>If the host platform is compromised or misconfigured this may contain untrustworthy root
     * certificates. Applications that connect to a known set of servers may be able to mitigate
     * this problem with {@linkplain CertificatePinner certificate pinning}.
     */
    public Builder addPlatformTrustedCertificates() {
      X509TrustManager platformTrustManager = Util.platformTrustManager();
      Collections.addAll(trustedCertificates, platformTrustManager.getAcceptedIssuers());
      return this;
    }

    public HandshakeCertificates build() {
      try {
        X509KeyManager keyManager = newKeyManager(null, heldCertificate, intermediates);
        X509TrustManager trustManager = newTrustManager(null, trustedCertificates);
        return new HandshakeCertificates(keyManager, trustManager);
      } catch (GeneralSecurityException gse) {
        throw new AssertionError(gse);
      }
    }
  }
}
