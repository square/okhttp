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
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Random;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/**
 * Constructs an SSL context for testing. This uses Bouncy Castle to generate a
 * self-signed certificate for a single hostname such as "localhost".
 *
 * <p>The crypto performed by this class is relatively slow. Clients should
 * reuse SSL context instances where possible.
 */
public final class SslContextBuilder {
  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  private static final long ONE_DAY_MILLIS = 1000L * 60 * 60 * 24;
  private final String hostName;
  private long notBefore = System.currentTimeMillis();
  private long notAfter = System.currentTimeMillis() + ONE_DAY_MILLIS;

  /**
   * @param hostName the subject of the host. For TLS this should be the
   * domain name that the client uses to identify the server.
   */
  public SslContextBuilder(String hostName) {
    this.hostName = hostName;
  }

  public SSLContext build() throws GeneralSecurityException {
    char[] password = "password".toCharArray();

    // Generate public and private keys and use them to make a self-signed certificate.
    KeyPair keyPair = generateKeyPair();
    X509Certificate certificate = selfSignedCertificate(keyPair);

    // Put 'em in a key store.
    KeyStore keyStore = newEmptyKeyStore(password);
    Certificate[] certificateChain = { certificate };
    keyStore.setKeyEntry("private", keyPair.getPrivate(), password, certificateChain);
    keyStore.setCertificateEntry("cert", certificate);

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

  private KeyPair generateKeyPair() throws GeneralSecurityException {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
    keyPairGenerator.initialize(1024, new SecureRandom());
    return keyPairGenerator.generateKeyPair();
  }

  /**
   * Generates a certificate for {@code hostName} containing {@code keyPair}'s
   * public key, signed by {@code keyPair}'s private key.
   */
  @SuppressWarnings("deprecation") // use the old Bouncy Castle APIs to reduce dependencies.
  private X509Certificate selfSignedCertificate(KeyPair keyPair) throws GeneralSecurityException {
    X509V3CertificateGenerator generator = new X509V3CertificateGenerator();
    X500Principal issuer = new X500Principal("CN=" + hostName);
    X500Principal subject = new X500Principal("CN=" + hostName);
    generator.setSerialNumber(new BigInteger(128, new Random()));
    generator.setIssuerDN(issuer);
    generator.setNotBefore(new Date(notBefore));
    generator.setNotAfter(new Date(notAfter));
    generator.setSubjectDN(subject);
    generator.setPublicKey(keyPair.getPublic());
    generator.setSignatureAlgorithm("SHA256WithRSAEncryption");
    return generator.generateX509Certificate(keyPair.getPrivate(), "BC");
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
