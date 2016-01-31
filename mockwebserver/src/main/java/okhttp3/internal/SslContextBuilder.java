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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Constructs an SSL context for testing. This uses Bouncy Castle to generate a self-signed
 * certificate for a single hostname such as "localhost".
 *
 * <p>The crypto performed by this class is relatively slow. Clients should reuse SSL context
 * instances where possible.
 */
public final class SslContextBuilder {
  private static SSLContext localhost; // Lazily initialized.
  private final String hostName;

  /**
   * @param hostName the subject of the host. For TLS this should be the domain name that the client
   * uses to identify the server.
   */
  public SslContextBuilder(String hostName) {
    this.hostName = hostName;
  }

  /** Returns a new SSL context for this host's current localhost address. */
  public static synchronized SSLContext localhost() {
    if (localhost == null) {
      try {
        localhost = new SslContextBuilder(InetAddress.getByName("localhost").getHostName()).build();
      } catch (GeneralSecurityException e) {
        throw new RuntimeException(e);
      } catch (UnknownHostException e) {
        throw new RuntimeException(e);
      }
    }
    return localhost;
  }

  public SSLContext build() throws GeneralSecurityException {
    // Generate a self-signed cert for the server to serve and the client to trust.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .serialNumber("1")
        .hostname(hostName)
        .build();

    // Put the certificate in a key store.
    char[] password = "password".toCharArray();
    KeyStore keyStore = newEmptyKeyStore(password);
    Certificate[] certificateChain = {heldCertificate.certificate};
    keyStore.setKeyEntry("private",
        heldCertificate.keyPair.getPrivate(), password, certificateChain);
    keyStore.setCertificateEntry("cert", heldCertificate.certificate);

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
