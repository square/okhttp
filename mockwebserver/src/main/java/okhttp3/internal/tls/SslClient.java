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
package okhttp3.internal.tls;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.platform.Platform;

/**
 * Combines an SSL socket factory and trust manager, a pairing enough for OkHttp or MockWebServer to
 * create a secure connection.
 */
public final class SslClient {
  private static SslClient localhost; // Lazily initialized.

  public final SSLContext sslContext;
  public final SSLSocketFactory socketFactory;
  public final X509TrustManager trustManager;

  private SslClient(SSLContext sslContext, X509TrustManager trustManager) {
    this.sslContext = sslContext;
    this.socketFactory = sslContext.getSocketFactory();
    this.trustManager = trustManager;
  }

  /** Returns an SSL client for this host's localhost address. */
  public static synchronized SslClient localhost() {
    if (localhost != null) return localhost;

    try {
      // Generate a self-signed cert for the server to serve and the client to trust.
      HeldCertificate heldCertificate = new HeldCertificate.Builder()
          .serialNumber("1")
          .commonName(InetAddress.getByName("localhost").getHostName())
          .build();

      localhost = new Builder()
          .certificateChain(heldCertificate.keyPair, heldCertificate.certificate)
          .addTrustedCertificate(heldCertificate.certificate)
          .build();

      return localhost;
    } catch (GeneralSecurityException | UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  public static class Builder {
    private final List<X509Certificate> chainCertificates = new ArrayList<>();
    private final List<X509Certificate> certificates = new ArrayList<>();
    private KeyPair keyPair;
    private String keyStoreType = KeyStore.getDefaultType();
    private SSLContext sslContext = null;

    /**
     * Configure the certificate chain to use when serving HTTPS responses. The first certificate is
     * the server's certificate, further certificates are included in the handshake so the client
     * can build a trusted path to a CA certificate.
     */
    public Builder certificateChain(HeldCertificate localCert, HeldCertificate... chain) {
      X509Certificate[] certificates = new X509Certificate[chain.length];
      for (int i = 0; i < chain.length; i++) {
        certificates[i] = chain[i].certificate;
      }
      return certificateChain(localCert.keyPair, localCert.certificate, certificates);
    }

    public Builder certificateChain(KeyPair keyPair, X509Certificate keyCert,
        X509Certificate... certificates) {
      this.keyPair = keyPair;
      this.chainCertificates.add(keyCert);
      this.chainCertificates.addAll(Arrays.asList(certificates));
      this.certificates.addAll(Arrays.asList(certificates));
      return this;
    }

    /**
     * Add a certificate authority that this client trusts. Servers that provide certificate chains
     * signed by these roots (or their intermediates) will be accepted.
     */
    public Builder addTrustedCertificate(X509Certificate certificate) {
      this.certificates.add(certificate);
      return this;
    }

    public Builder keyStoreType(String keyStoreType) {
      this.keyStoreType = keyStoreType;
      return this;
    }

    public Builder setSslContext(SSLContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    public SslClient build() {
      try {
        // Put the certificate in a key store.
        char[] password = "password".toCharArray();
        KeyStore keyStore = newEmptyKeyStore(password);

        if (keyPair != null) {
          Certificate[] certificates = chainCertificates.toArray(
              new Certificate[chainCertificates.size()]);
          keyStore.setKeyEntry("private", keyPair.getPrivate(), password, certificates);
        }

        for (int i = 0; i < certificates.size(); i++) {
          keyStore.setCertificateEntry("cert_" + i, certificates.get(i));
        }

        // Wrap it up in an SSL context.
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
          throw new IllegalStateException("Unexpected default trust managers:"
              + Arrays.toString(trustManagers));
        }

        SSLContext activeSslContext =
            this.sslContext != null ? this.sslContext : Platform.get().getSSLContext();
        activeSslContext.init(keyManagerFactory.getKeyManagers(), trustManagers,
            new SecureRandom());

        return new SslClient(activeSslContext, (X509TrustManager) trustManagers[0]);
      } catch (GeneralSecurityException gse) {
        throw new AssertionError(gse);
      }
    }

    private KeyStore newEmptyKeyStore(char[] password) throws GeneralSecurityException {
      try {
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        InputStream in = null; // By convention, 'null' creates an empty key store.
        keyStore.load(in, password);
        return keyStore;
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }
}
