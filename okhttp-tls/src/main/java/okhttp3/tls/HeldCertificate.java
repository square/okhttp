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
package okhttp3.tls;

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
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
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
 * A certificate and its private key. These are some properties of certificates that are used with
 * TLS:
 *
 * <ul>
 *   <li><strong>A common name.</strong> This is a string identifier for the certificate. It usually
 *       describes the purpose of the certificate like "Entrust Root Certification Authority - G2"
 *       or "www.squareup.com".
 *   <li><strong>A set of hostnames.</strong> These are in the certificate's subject alternative
 *       name (SAN) extension. A subject alternative name is either a literal hostname ({@code
 *       squareup.com}), a literal IP address ({@code 74.122.190.80}), or a hostname pattern ({@code
 *       *.api.squareup.com}).
 *   <li><strong>A validity interval.</strong> A certificate should not be used before its validity
 *       interval starts or after it ends.
 *   <li><strong>A public key.</strong> This cryptographic key is used for asymmetric encryption
 *       digital signatures. Note that the private key is not a part of the certificate!
 *   <li><strong>A signature issued by another certificate's private key.</strong> This mechanism
 *       allows a trusted third-party to endorse a certificate. Third parties should only endorse
 *       certificates once they've confirmed that the owner of the private key is also the owner of
 *       the certificate's other properties.
 * </ul>
 *
 * <p>Certificates are signed by other certificates and a sequence of them is called a certificate
 * chain. The chain terminates in a self-signed "root" certificate. Signing certificates in the
 * middle of the chain are called "intermediates". Organizations that offer certificate signing are
 * called certificate authorities (CAs).
 *
 * <p>Browsers and other HTTP clients need a set of trusted root certificates to authenticate their
 * peers. Sets of root certificates are managed by either the HTTP client (like Firefox), or the
 * host platform (like Android). In July 2018 Android had 134 trusted root certificates for its HTTP
 * clients to trust.
 *
 * <p>For example, in order to establish a secure connection to {@code https://www.squareup.com/},
 * these three certificates are used. <pre>{@code
 *
 * www.squareup.com certificate:
 *
 *   Common Name: www.squareup.com
 *   Subject Alternative Names: www.squareup.com, squareup.com, account.squareup.com...
 *   Validity: 2018-07-03T20:18:17Z – 2019-08-01T20:48:15Z
 *   Public Key: d107beecc17325f55da976bcbab207ba4df68bd3f8fce7c3b5850311128264fd53e1baa342f58d93...
 *   Signature: 1fb0e66fac05322721fe3a3917f7c98dee1729af39c99eab415f22d8347b508acdf0bab91781c3720...
 *
 * signed by intermediate certificate:
 *
 *   Common Name: Entrust Certification Authority - L1M
 *   Subject Alternative Names: none
 *   Validity: 2014-12-15T15:25:03Z – 2030-10-15T15:55:03Z
 *   Public Key: d081c13923c2b1d1ecf757dd55243691202248f7fcca520ab0ab3f33b5b08407f6df4e7ab0fb9822...
 *   Signature: b487c784221a29c0a478ecf54f1bb484976f77eed4cf59afa843962f1d58dea6f3155b2ed9439c4c4...
 *
 * signed by root certificate:
 *
 *   Common Name: Entrust Root Certification Authority - G2
 *   Subject Alternative Names: none
 *   Validity: 2009-07-07T17:25:54Z – 2030-12-07T17:55:54Z
 *   Public Key: ba84b672db9e0c6be299e93001a776ea32b895411ac9da614e5872cffef68279bf7361060aa527d8...
 *   Self-signed Signature: 799f1d96c6b6793f228d87d3870304606a6b9a2e59897311ac43d1f513ff8d392bc0f...
 *
 * }</pre>
 *
 * <p>In this example the HTTP client already knows and trusts the last certificate, "Entrust Root
 * Certification Authority - G2". That certificate is used to verify the signature of the
 * intermediate certificate, "Entrust Certification Authority - L1M". The intermediate certificate
 * is used to verify the signature of the "www.squareup.com" certificate.
 *
 * <p>This roles are reversed for client authentication. In that case the client has a private key
 * and a chain of certificates. The server uses a set of trusted root certificates to authenticate
 * the client. Subject alternative names are not used for client authentication.
 */
public final class HeldCertificate {
  private final X509Certificate certificate;
  private final KeyPair keyPair;

  public HeldCertificate(KeyPair keyPair, X509Certificate certificate) {
    if (keyPair == null) throw new NullPointerException("keyPair == null");
    if (certificate == null) throw new NullPointerException("certificate == null");
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
   * Returns the RSA private key encoded in <a href="https://tools.ietf.org/html/rfc5208">PKCS
   * #8</a> <a href="https://tools.ietf.org/html/rfc7468">PEM format</a>.
   */
  public String privateKeyPkcs8Pem() {
    StringBuilder result = new StringBuilder();
    result.append("-----BEGIN PRIVATE KEY-----\n");
    encodeBase64Lines(result, ByteString.of(keyPair.getPrivate().getEncoded()));
    result.append("-----END PRIVATE KEY-----\n");
    return result.toString();
  }

  /**
   * Returns the RSA private key encoded in <a href="https://tools.ietf.org/html/rfc8017">PKCS
   * #1</a> <a href="https://tools.ietf.org/html/rfc7468">PEM format</a>.
   */
  public String privateKeyPkcs1Pem() {
    if (!(keyPair.getPrivate() instanceof RSAPrivateKey)) {
      throw new IllegalStateException("PKCS1 only supports RSA keys");
    }
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

  /** Build a held certificate with reasonable defaults. */
  public static final class Builder {
    private static final long DEFAULT_DURATION_MILLIS = 1000L * 60 * 60 * 24; // 24 hours.

    static {
      Security.addProvider(new BouncyCastleProvider());
    }

    private long notBefore = -1L;
    private long notAfter = -1L;
    private @Nullable String cn;
    private @Nullable String ou;
    private final List<String> altNames = new ArrayList<>();
    private @Nullable BigInteger serialNumber;
    private @Nullable KeyPair keyPair;
    private @Nullable HeldCertificate signedBy;
    private int maxIntermediateCas = -1;
    private @Nullable String keyAlgorithm;
    private int keySize;

    public Builder() {
      ecdsa256();
    }

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
     * Adds a subject alternative name (SAN) to the certificate. This is usually a literal hostname,
     * a literal IP address, or a hostname pattern. If no subject alternative names are added that
     * extension will be omitted.
     */
    public Builder addSubjectAlternativeName(String altName) {
      if (altName == null) throw new NullPointerException("altName == null");
      altNames.add(altName);
      return this;
    }

    /**
     * Set this certificate's common name (CN). Historically this held the hostname of TLS
     * certificate, but that practice was deprecated by <a
     * href="https://tools.ietf.org/html/rfc2818">RFC 2818</a> and replaced with {@link
     * #addSubjectAlternativeName(String) subject alternative names}. If unset a random string will
     * be used.
     */
    public Builder commonName(String cn) {
      this.cn = cn;
      return this;
    }

    /** Sets the certificate's organizational unit (OU). If unset this field will be omitted. */
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
     * Set the certificate that will issue this certificate. If unset the certificate will be
     * self-signed.
     */
    public Builder signedBy(HeldCertificate signedBy) {
      this.signedBy = signedBy;
      return this;
    }

    /**
     * Set this certificate to be a signing certificate, with up to {@code maxIntermediateCas}
     * intermediate signing certificates beneath it.
     *
     * <p>By default this certificate cannot not sign other certificates. Set this to 0 so this
     * certificate can sign other certificates (but those certificates cannot themselves sign
     * certificates). Set this to 1 so this certificate can sign intermediate certificates that can
     * themselves sign certificates. Add one for each additional layer of intermediates to permit.
     */
    public Builder certificateAuthority(int maxIntermediateCas) {
      if (maxIntermediateCas < 0) {
        throw new IllegalArgumentException("maxIntermediateCas < 0: " + maxIntermediateCas);
      }
      this.maxIntermediateCas = maxIntermediateCas;
      return this;
    }

    /**
     * Configure the certificate to generate a 256-bit ECDSA key, which provides about 128 bits of
     * security. ECDSA keys are noticeably faster than RSA keys.
     *
     * <p>This is the default configuration and has been since this API was introduced in OkHttp
     * 3.11.0. Note that the default may change in future releases.
     */
    public Builder ecdsa256() {
      keyAlgorithm = "ECDSA";
      keySize = 256;
      return this;
    }

    /**
     * Configure the certificate to generate a 2048-bit RSA key, which provides about 112 bits of
     * security. RSA keys are interoperable with very old clients that don't support ECDSA.
     */
    public Builder rsa2048() {
      keyAlgorithm = "RSA";
      keySize = 2048;
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
      if (signedBy != null) {
        signedByKeyPair = signedBy.keyPair;
        signedByPrincipal = signedBy.certificate.getSubjectX500Principal();
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
      generator.setSignatureAlgorithm(signedByKeyPair.getPrivate() instanceof RSAPrivateKey
          ? "SHA256WithRSAEncryption"
          : "SHA256withECDSA");

      if (maxIntermediateCas != -1) {
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
            signedByKeyPair.getPrivate());
        return new HeldCertificate(heldKeyPair, certificate);
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
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(keyAlgorithm);
        keyPairGenerator.initialize(keySize, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(e);
      }
    }
  }
}
