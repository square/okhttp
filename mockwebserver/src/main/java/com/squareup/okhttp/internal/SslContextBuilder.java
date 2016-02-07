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

package com.squareup.okhttp.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Constructs an SSL context for testing. This uses Bouncy Castle to generate a
 * self-signed certificate for a single hostname such as "localhost".
 *
 * <p>The crypto performed by this class is relatively slow. Clients should
 * reuse SSL context instances where possible.
 */
public final class SslContextBuilder {
  private static SSLContext localhost; // Lazily initialized.

  /** Returns a new SSL context for this host's current localhost address. */
  public static synchronized SSLContext localhost() {
    if (localhost != null) return localhost;

    try {
      // Generate a self-signed cert for the server to serve and the client to trust.
      HeldCertificate heldCertificate = new HeldCertificate.Builder()
          .serialNumber("1")
          .commonName(InetAddress.getByName("localhost").getHostName())
          .build();

      localhost = new SslContextBuilder()
          .certificateChain(heldCertificate)
          .addTrustedCertificate(heldCertificate.certificate)
          .build();

      return localhost;
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private HeldCertificate[] chain;
  private List<X509Certificate> trustedCertificates = new ArrayList<>();

  /**
   * Configure the certificate chain to use when serving HTTPS responses. The first certificate
   * in this chain is the server's certificate, further certificates are included in the handshake
   * so the client can build a trusted path to a CA certificate.
   */
  public SslContextBuilder certificateChain(HeldCertificate... chain) {
    this.chain = chain;
    return this;
  }

  /**
   * Add a certificate authority that this client trusts. Servers that provide certificate chains
   * signed by these roots (or their intermediates) will be accepted.
   */
  public SslContextBuilder addTrustedCertificate(X509Certificate certificate) {
    trustedCertificates.add(certificate);
    return this;
  }

  public SSLContext build() throws GeneralSecurityException {
    // Put the certificate in a key store.
    char[] password = "password".toCharArray();
    KeyStore keyStore = newEmptyKeyStore(password);

    if (chain != null) {
      Certificate[] certificates = new Certificate[chain.length];
      for (int i = 0; i < chain.length; i++) {
        certificates[i] = chain[i].certificate;
      }
      keyStore.setKeyEntry("private", chain[0].keyPair.getPrivate(), password, certificates);
    }

    for (int i = 0; i < trustedCertificates.size(); i++) {
      keyStore.setCertificateEntry("cert_" + i, trustedCertificates.get(i));
    }

    // Wrap it up in an SSL context.
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password);
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
        new SecureRandom());
    return sslContext;
  }

  private KeyStore newEmptyKeyStore(char[] password) throws GeneralSecurityException {
    try {
      KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      InputStream in = null; // By convention, 'null' creates an empty key store.
      keyStore.load(in, password);
      return keyStore;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
