/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.tls.SslClient;

/**
 * Constructs an SSL context for testing. This uses Bouncy Castle to generate a self-signed
 * certificate for a single hostname such as "localhost".
 *
 * <p>The crypto performed by this class is relatively slow. Clients should reuse SSL context
 * instances where possible.
 */
public final class SslContextBuilder {
  private static SslClient localhost; // Lazily initialized.

  private SslClient.Builder sslClientBuilder = new SslClient.Builder();
  private SslClient sslClient;

  /** Returns a new SSL context for this host's current localhost address. */
  public static synchronized SslClient localhost() {
    if (localhost != null) return localhost;

    try {
      // Generate a self-signed cert for the server to serve and the client to trust.
      HeldCertificate heldCertificate = new HeldCertificate.Builder()
          .serialNumber("1")
          .commonName(InetAddress.getByName("localhost").getHostName())
          .build();

      localhost = new SslClient.Builder()
          .certificateChain(heldCertificate.keyPair, heldCertificate.certificate)
          .addTrustedCertificate(heldCertificate.certificate)
          .build();

      return localhost;
    } catch (GeneralSecurityException | UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Configure the certificate chain to use when serving HTTPS responses. The first certificate
   * is the server's certificate, further certificates are included in the handshake
   * so the client can build a trusted path to a CA certificate.
   */
  public SslContextBuilder certificateChain(HeldCertificate serverCert, HeldCertificate... chain) {
    X509Certificate[] certificates = new X509Certificate[chain.length];
    for (int i = 0; i < chain.length; i++) {
      certificates[i] = chain[i].certificate;
    }
    sslClientBuilder.certificateChain(serverCert.keyPair, serverCert.certificate, certificates);
    return this;
  }

  /**
   * Add a certificate authority that this client trusts. Servers that provide certificate chains
   * signed by these roots (or their intermediates) will be accepted.
   */
  public SslContextBuilder addTrustedCertificate(X509Certificate certificate) {
    sslClientBuilder.addTrustedCertificate(certificate);
    return this;
  }

  public SSLContext build() {
    sslClient = sslClientBuilder.build();
    return sslClient.sslContext;
  }

  public SSLSocketFactory socketFactory() {
    if (sslClient == null) {
      build();
    }

    return sslClient.sslContext.getSocketFactory();
  }

  public X509TrustManager trustManager() {
    if (sslClient == null) {
      build();
    }

    return sslClient.trustManager;
  }
}
