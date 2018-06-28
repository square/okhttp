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
package okhttp3.mockwebserver;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.security.auth.x500.X500Principal;
import okio.ByteString;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import static okhttp3.internal.Util.verifyAsIpAddress;

/**
 * A certificate and its private key.
 *
 * <p>Typically the certificate and private key combination would be used by MockWebServer (or
 * another HTTPS server) to identify itself in the TLS handshake. The certificate alone can be used
 * by OkHttp (or another HTTPS client) to verify the identity of that server.
 *
 * <p>The trust challenge is reversed for mutual auth. In this case the client has both the private
 * key and the certificate, and the server has a certificate only.
 *
 * <p>In addition to the TLS handshake, a held certificate can be used to sign a different
 * certificate. In such cases the held certificate represents a certificate authority.
 *
 * <p>This class is intended to be used for testing. It uses small keys (1024 bit RSA) because they
 * are quick to generate.
 */
public final class HeldCertificate {
  private final X509Certificate certificate;
  private final KeyPair keyPair;

  private HeldCertificate(X509Certificate certificate, KeyPair keyPair) {
    this.certificate = certificate;
    this.keyPair = keyPair;
  }

  public X509Certificate certificate() {
    return certificate;
  }

  public KeyPair keyPair() {
    return keyPair;
  }

  /**
   * Returns the certificate encoded in <a href="https://tools.ietf.org/html/rfc7468">PEM
   * format</a>.
   */
  public String certificatePem() {
    try {
      StringBuilder result = new StringBuilder();
      result.append("-----BEGIN CERTIFICATE-----\n");
      encodeBase64Lines(result, ByteString.of(certificate.getEncoded()));
      result.append("-----END CERTIFICATE-----\n");
      return result.toString();
    } catch (CertificateEncodingException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Returns the RSA private key encoded in <a href="https://tools.ietf.org/html/rfc8017">PKCS
   * #1</a> <a href="https://tools.ietf.org/html/rfc7468">PEM format</a>.
   */
  public String privateKeyPkcs8Pem() {
    StringBuilder result = new StringBuilder();
    result.append("-----BEGIN PRIVATE KEY-----\n");
    encodeBase64Lines(result, ByteString.of(keyPair.getPrivate().getEncoded()));
    result.append("-----END PRIVATE KEY-----\n");
    return result.toString();
  }

  /**
   * Returns the RSA private key encoded in <a href="https://tools.ietf.org/html/rfc5208">PKCS
   * #8</a> <a href="https://tools.ietf.org/html/rfc7468">PEM format</a>.
   */
  public String privateKeyPkcs1Pem() {
    StringBuilder result = new StringBuilder();
    result.append("-----BEGIN RSA PRIVATE KEY-----\n");
    encodeBase64Lines(result, pkcs1Bytes());
    result.append("-----END RSA PRIVATE KEY-----\n");
    return result.toString();
  }

  private ByteString pkcs1Bytes() {
    try {
      PrivateKeyInfo privateKeyInfo = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded());
      return ByteString.of(privateKeyInfo.parsePrivateKey().toASN1Primitive().getEncoded());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private void encodeBase64Lines(StringBuilder out, ByteString data) {
    String base64 = data.base64();
    for (int i = 0; i < base64.length(); i += 64) {
      out.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
    }
  }

  /** Build a held certificate with reasonable defaults for testing. */
  public static final class Builder {
    private static final long DEFAULT_DURATION_MILLIS = 1000L * 60 * 60 * 24; // 24 hours.

    static {
      Security.addProvider(new BouncyCastleProvider());
    }

    private long notBefore = -1L;
    private long notAfter = -1L;
    private String cn;
    private String ou;
    private final List<String> altNames = new ArrayList<>();
    private BigInteger serialNumber;
    private KeyPair keyPair;
    private HeldCertificate issuedBy;
    private int maxIntermediateCas;

    /**
     * Sets the certificate to be valid in {@code [notBefore..notAfter]}. Both endpoints are
     * specified in the format of {@link System#currentTimeMillis()}. Specify -1L for both values
     * to use the default interval, 24 hours starting when the certificate is created.
     */
    public Builder validityInterval(long notBefore, long notAfter) {
      if (notBefore > notAfter || (notBefore == -1L) != (notAfter == -1L)) {
        throw new IllegalArgumentException("invalid interval: " + notBefore + ".." + notAfter);
      }
      this.notBefore = notBefore;
      this.notAfter = notAfter;
      return this;
    }

    /**
     * Sets the certificate to be valid immediately and until the specified duration has elapsed.
     * The precision of this field is seconds; further precision will be truncated.
     */
    public Builder duration(long duration, TimeUnit unit) {
      long now = System.currentTimeMillis();
      return validityInterval(now, now + unit.toMillis(duration));
    }

    /**
     * Adds a subject alternative name (SAN) to the certificate. This is usually a hostname or IP
     * address. If no subject alternative names are added that extension will not be used.
     */
    public Builder addSubjectAlternativeName(String altName) {
      if (altName == null) throw new NullPointerException("altName == null");
      altNames.add(altName);
      return this;
    }

    /**
     * Set this certificate's common name. Historically this held the hostname of TLS certificate,
     * but that practice was deprecated by <a href="https://tools.ietf.org/html/rfc2818">RFC
     * 2818</a> and replaced with {@link #addSubjectAlternativeName(String) subject alternative
     * names}. If unset a random string will be used.
     */
    public Builder commonName(String cn) {
      this.cn = cn;
      return this;
    }

    /** Sets the certificate's organizational unit. If unset this field will be omitted. */
    public Builder organizationalUnit(String ou) {
      this.ou = ou;
      return this;
    }

    /** Sets this certificate's serial number. If unset the serial number will be 1. */
    public Builder serialNumber(BigInteger serialNumber) {
      this.serialNumber = serialNumber;
      return this;
    }

    /** Sets this certificate's serial number. If unset the serial number will be 1. */
    public Builder serialNumber(long serialNumber) {
      return serialNumber(BigInteger.valueOf(serialNumber));
    }

    /**
     * Sets the public/private key pair used for this certificate. If unset a key pair will be
     * generated.
     */
    public Builder keyPair(KeyPair keyPair) {
      this.keyPair = keyPair;
      return this;
    }

    /**
     * Sets the public/private key pair used for this certificate. If unset a key pair will be
     * generated.
     */
    public Builder keyPair(PublicKey publicKey, PrivateKey privateKey) {
      return keyPair(new KeyPair(publicKey, privateKey));
    }

    /**
     * Set the certificate that signs this certificate. If unset the certificate will be
     * self-signed.
     */
    public Builder issuedBy(HeldCertificate issuedBy) {
      this.issuedBy = issuedBy;
      return this;
    }

    /**
     * Set this certificate to be a certificate authority, with up to {@code maxIntermediateCas}
     * intermediate certificate authorities beneath it.
     */
    public Builder certificateAuthority(int maxIntermediateCas) {
      this.maxIntermediateCas = maxIntermediateCas;
      return this;
    }

    public HeldCertificate build() {
      // Subject, public & private keys for this certificate.
      KeyPair heldKeyPair = keyPair != null
          ? keyPair
          : generateKeyPair();

      X500Principal subject = buildSubject();

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
      long notBefore = this.notBefore != -1L ? this.notBefore : System.currentTimeMillis();
      long notAfter = this.notAfter != -1L ? this.notAfter : notBefore + DEFAULT_DURATION_MILLIS;
      BigInteger serialNumber = this.serialNumber != null ? this.serialNumber : BigInteger.ONE;
      X509V3CertificateGenerator generator = new X509V3CertificateGenerator();
      generator.setSerialNumber(serialNumber);
      generator.setIssuerDN(signedByPrincipal);
      generator.setNotBefore(new Date(notBefore));
      generator.setNotAfter(new Date(notAfter));
      generator.setSubjectDN(subject);
      generator.setPublicKey(heldKeyPair.getPublic());
      generator.setSignatureAlgorithm("SHA256WithRSAEncryption");

      if (maxIntermediateCas > 0) {
        generator.addExtension(X509Extensions.BasicConstraints, true,
            new BasicConstraints(maxIntermediateCas));
      }

      if (!altNames.isEmpty()) {
        ASN1Encodable[] encodableAltNames = new ASN1Encodable[altNames.size()];
        for (int i = 0, size = altNames.size(); i < size; i++) {
          String altName = altNames.get(i);
          int tag = verifyAsIpAddress(altName)
              ? GeneralName.iPAddress
              : GeneralName.dNSName;
          encodableAltNames[i] = new GeneralName(tag, altName);
        }
        generator.addExtension(X509Extensions.SubjectAlternativeName, true,
            new DERSequence(encodableAltNames));
      }

      try {
        X509Certificate certificate = generator.generateX509Certificate(
            signedByKeyPair.getPrivate(), "BC");
        return new HeldCertificate(certificate, heldKeyPair);
      } catch (GeneralSecurityException e) {
        throw new AssertionError(e);
      }
    }

    private X500Principal buildSubject() {
      StringBuilder nameBuilder = new StringBuilder();
      if (cn != null) {
        nameBuilder.append("CN=").append(cn);
      } else {
        nameBuilder.append("CN=").append(UUID.randomUUID());
      }
      if (ou != null) {
        nameBuilder.append(", OU=").append(ou);
      }
      return new X500Principal(nameBuilder.toString());
    }

    private KeyPair generateKeyPair() {
      try {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
        keyPairGenerator.initialize(1024, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(e);
      }
    }
  }
}
