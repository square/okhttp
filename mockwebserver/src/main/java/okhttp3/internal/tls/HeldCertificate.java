/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.tls;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/**
 * A certificate and its private key. This can be used on the server side by HTTPS servers, or on
 * the client side to verify those HTTPS servers. A held certificate can also be used to sign other
 * held certificates, as done in practice by certificate authorities.
 */
public final class HeldCertificate {
  public final X509Certificate certificate;
  public final KeyPair keyPair;

  public HeldCertificate(X509Certificate certificate, KeyPair keyPair) {
    this.certificate = certificate;
    this.keyPair = keyPair;
  }

  public static final class Builder {
    static {
      Security.addProvider(new BouncyCastleProvider());
    }

    private final long duration = 1000L * 60 * 60 * 24; // One day.
    private String hostname;
    private String serialNumber = "1";
    private KeyPair keyPair;
    private HeldCertificate issuedBy;
    private int maxIntermediateCas;

    public Builder serialNumber(String serialNumber) {
      this.serialNumber = serialNumber;
      return this;
    }

    /**
     * Set this certificate's name. Typically this is the URL hostname for TLS certificates. This is
     * the CN (common name) in the certificate. Will be a random string if no value is provided.
     */
    public Builder commonName(String hostname) {
      this.hostname = hostname;
      return this;
    }

    public Builder keyPair(KeyPair keyPair) {
      this.keyPair = keyPair;
      return this;
    }

    /**
     * Set the certificate that signs this certificate. If unset, a self-signed certificate will be
     * generated.
     */
    public Builder issuedBy(HeldCertificate signedBy) {
      this.issuedBy = signedBy;
      return this;
    }

    /**
     * Set this certificate to be a certificate authority, with up to {@code maxIntermediateCas}
     * intermediate certificate authorities beneath it.
     */
    public Builder ca(int maxIntermediateCas) {
      this.maxIntermediateCas = maxIntermediateCas;
      return this;
    }

    public HeldCertificate build() throws GeneralSecurityException {
      // Subject, public & private keys for this certificate.
      KeyPair heldKeyPair = keyPair != null
          ? keyPair
          : generateKeyPair();
      X500Principal subject = hostname != null
          ? new X500Principal("CN=" + hostname)
          : new X500Principal("CN=" + UUID.randomUUID());

      // Subject, public & private keys for this certificate's signer. It may be self signed!
      KeyPair signedByKeyPair;
      X500Principal signedByPrincipal;
      if (issuedBy != null) {
        signedByKeyPair = issuedBy.keyPair;
        signedByPrincipal = issuedBy.certificate.getSubjectX500Principal();
      } else {
        signedByKeyPair = heldKeyPair;
        signedByPrincipal = subject;
      }

      // Generate & sign the certificate.
      long now = System.currentTimeMillis();
      X509V3CertificateGenerator generator = new X509V3CertificateGenerator();
      generator.setSerialNumber(new BigInteger(serialNumber));
      generator.setIssuerDN(signedByPrincipal);
      generator.setNotBefore(new Date(now));
      generator.setNotAfter(new Date(now + duration));
      generator.setSubjectDN(subject);
      generator.setPublicKey(heldKeyPair.getPublic());
      generator.setSignatureAlgorithm("SHA256WithRSAEncryption");

      if (maxIntermediateCas > 0) {
        generator.addExtension(X509Extensions.BasicConstraints, true,
            new BasicConstraints(maxIntermediateCas));
      }

      X509Certificate certificate = generator.generateX509Certificate(
          signedByKeyPair.getPrivate(), "BC");
      return new HeldCertificate(certificate, heldKeyPair);
    }

    public KeyPair generateKeyPair() throws GeneralSecurityException {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
      keyPairGenerator.initialize(1024, new SecureRandom());
      return keyPairGenerator.generateKeyPair();
    }
  }
}
