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
package okhttp3.tls.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.tls.HeldCertificate;
import okhttp3.tls.HandshakeCertificates;

public final class TlsUtil {
  public static final char[] password = "password".toCharArray();
  private static HandshakeCertificates localhost; // Lazily initialized.

  private TlsUtil() {
  }

  /** Returns an SSL client for this host's localhost address. */
  public static synchronized HandshakeCertificates localhost() {
    if (localhost != null) return localhost;

    try {
      // Generate a self-signed cert for the server to serve and the client to trust.
      HeldCertificate heldCertificate = new HeldCertificate.Builder()
          .commonName("localhost")
          .addSubjectAlternativeName(InetAddress.getByName("localhost").getCanonicalHostName())
          .build();

      localhost = new HandshakeCertificates.Builder()
          .heldCertificate(heldCertificate)
          .addTrustedCertificate(heldCertificate.certificate())
          .build();

      return localhost;
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns a trust manager that trusts {@code trustedCertificates}. */
  public static X509TrustManager newTrustManager(String keyStoreType,
      List<X509Certificate> trustedCertificates) throws GeneralSecurityException {
    KeyStore trustStore = newEmptyKeyStore(keyStoreType);
    for (int i = 0; i < trustedCertificates.size(); i++) {
      trustStore.setCertificateEntry("cert_" + i, trustedCertificates.get(i));
    }
    TrustManagerFactory factory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    TrustManager[] result = factory.getTrustManagers();
    if (result.length != 1 || !(result[0] instanceof X509TrustManager)) {
      throw new IllegalStateException("Unexpected trust managers:" + Arrays.toString(result));
    }
    return (X509TrustManager) result[0];
  }

  /**
   * Returns a key manager for the held certificate and its chain. Returns an empty key manager if
   * {@code heldCertificate} is null.
   */
  public static X509KeyManager newKeyManager(String keyStoreType, HeldCertificate heldCertificate,
      X509Certificate... intermediates) throws GeneralSecurityException {
    KeyStore keyStore = newEmptyKeyStore(keyStoreType);

    if (heldCertificate != null) {
      Certificate[] chain = new Certificate[1 + intermediates.length];
      chain[0] = heldCertificate.certificate();
      System.arraycopy(intermediates, 0, chain, 1, intermediates.length);
      keyStore.setKeyEntry("private", heldCertificate.keyPair().getPrivate(), password, chain);
    }

    KeyManagerFactory factory = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
    factory.init(keyStore, password);
    KeyManager[] result = factory.getKeyManagers();
    if (result.length != 1 || !(result[0] instanceof X509KeyManager)) {
      throw new IllegalStateException("Unexpected key managers:" + Arrays.toString(result));
    }
    return (X509KeyManager) result[0];
  }

  private static KeyStore newEmptyKeyStore(String keyStoreType) throws GeneralSecurityException {
    if (keyStoreType == null) {
      keyStoreType = KeyStore.getDefaultType();
    }

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
