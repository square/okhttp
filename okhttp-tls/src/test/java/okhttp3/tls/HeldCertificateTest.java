/*
 * Copyright (C) 2018 Square, Inc.
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

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.TimeUnit;
import okio.ByteString;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.fail;

public final class HeldCertificateTest {
  @Test public void defaultCertificate() throws CertificateParsingException {
    long now = System.currentTimeMillis();
    HeldCertificate heldCertificate = new HeldCertificate.Builder().build();

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getSubjectX500Principal().getName()).overridingErrorMessage(
        "self-signed").isEqualTo(certificate.getIssuerX500Principal().getName());
    assertThat(certificate.getIssuerX500Principal().getName()).matches("CN=[0-9a-f-]{36}");
    assertThat(certificate.getSerialNumber()).isEqualTo(BigInteger.ONE);
    assertThat(certificate.getSubjectAlternativeNames()).isNull();

    double deltaMillis = 1000.0;
    long durationMillis = TimeUnit.MINUTES.toMillis(60 * 24);
    assertThat((double) certificate.getNotBefore().getTime()).isCloseTo(
        (double) now, offset(deltaMillis));
    assertThat((double) certificate.getNotAfter().getTime()).isCloseTo(
        (double) now + durationMillis, offset(deltaMillis));
  }

  @Test public void customInterval() {
    // 5 seconds starting on 1970-01-01.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .validityInterval(5_000L, 10_000L)
        .build();
    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getNotBefore().getTime()).isEqualTo(5_000L);
    assertThat(certificate.getNotAfter().getTime()).isEqualTo(10_000L);
  }

  @Test public void customDuration() {
    long now = System.currentTimeMillis();

    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .duration(5, TimeUnit.SECONDS)
        .build();
    X509Certificate certificate = heldCertificate.certificate();

    double deltaMillis = 1000.0;
    long durationMillis = 5_000L;
    assertThat((double) certificate.getNotBefore().getTime()).isCloseTo(
        (double) now, offset(deltaMillis));
    assertThat((double) certificate.getNotAfter().getTime()).isCloseTo(
        (double) now + durationMillis, offset(deltaMillis));
  }

  @Test public void subjectAlternativeNames() throws CertificateParsingException {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .addSubjectAlternativeName("1.1.1.1")
        .addSubjectAlternativeName("cash.app")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getSubjectAlternativeNames()).containsExactly(
        asList(GeneralName.iPAddress, "1.1.1.1"),
        asList(GeneralName.dNSName, "cash.app"));
  }

  @Test public void commonName() {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("cash.app")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=cash.app");
  }

  @Test public void organizationalUnit() {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("cash.app")
        .organizationalUnit("cash")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo(
        "CN=cash.app,OU=cash");
  }

  /** Confirm golden values of encoded PEMs. */
  @Test public void pems() throws Exception {
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");

    ByteString publicKeyBytes = ByteString.decodeBase64("MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApF"
        + "HhtrLan28q+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN89XK4UJVM6Sqp3"
        + "K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1aoI0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQAB");
    PublicKey publicKey = keyFactory.generatePublic(
        new X509EncodedKeySpec(publicKeyBytes.toByteArray()));

    ByteString privateKeyBytes = ByteString.decodeBase64("MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbA"
        + "gEAAoGBAICkUeG2stqfbyr6gyiVm5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z1"
        + "crhQlUzpKqncrzwqbzPuAyt2t9Oib/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgjSYigTHTEWxCEgnrfu/YzEkO6l"
        + "3rXAgMBAAECgYB99mhnB6piADOuddXv626NzUBTr4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPb"
        + "C8Di3sQSTnVGpSqAvEXknBMzIc0UO74Rn9p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1RsTmseYML"
        + "eJl5F79gQJBAO/BbAKqg1yzK7VijygvBoUrr+rt2lbmKgcUQ/rxu8IIQk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT"
        + "7xozvLzV7ECQQCJW59w7NIM0qZ/gIX2gcNZr1B/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJ"
        + "qpXZERa26UHAkBLXmiP5f5pk8F3wcXzAeVw06z3k1IB41Tu6MX+CyPU+TeudRlz+wV8b0zDvK+EnRKCCbptVFj1B"
        + "kt8lQ4JfcnhAkAk2Y3Gz+HySrkcT7Cg12M/NkdUQnZe3jr88pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXD"
        + "AZ6941XCsIVAkASOg02PlVHLidU7mIE65swMM5/RNhS4aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgbU"
        + "XH+NyxKwboE");
    PrivateKey privateKey = keyFactory.generatePrivate(
        new PKCS8EncodedKeySpec(privateKeyBytes.toByteArray()));

    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .keyPair(publicKey, privateKey)
        .commonName("cash.app")
        .validityInterval(0L, 1_000L)
        .rsa2048()
        .build();

    assertThat((""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo\n"
        + "LmFwcDAeFw03MDAxMDEwMDAwMDBaFw03MDAxMDEwMDAwMDFaMBMxETAPBgNVBAMT\n"
        + "CGNhc2guYXBwMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q\n"
        + "+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN\n"
        + "89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1ao\n"
        + "I0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQABMA0GCSqGSIb3DQEBCwUAA4GBADam\n"
        + "UVwKh5Ry7es3OxtY3IgQunPUoLc0Gw71gl9Z+7t2FJ5VkcI5gWfutmdxZ2bDXCI8\n"
        + "8V0vxo1pHXnbBrnxhS/Z3TBerw8RyQqcaWOdp+pBXyIWmR+jHk9cHZCqQveTIBsY\n"
        + "jaA9VEhgdaVhxBsT2qzUNDsXlOzGsliznDfoqETb\n"
        + "-----END CERTIFICATE-----\n")).isEqualTo(heldCertificate.certificatePem());

    assertThat((""
        + "-----BEGIN RSA PRIVATE KEY-----\n"
        + "MIICWwIBAAKBgQCApFHhtrLan28q+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1w\n"
        + "J4YEoUCjDlPOtpht7XLbUmBnbIzN89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274w\n"
        + "L25fICR+yDEQ5fUVYBmJAKXZF1aoI0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQAB\n"
        + "AoGAffZoZweqYgAzrnXV7+tujc1AU6+MW7GEU4EsR88H+dKBU0wUg7lvtSDgVck1\n"
        + "rveEkj8rdC5ZT2wvA4t7EEk51RqUqgLxF5JwTMyHNFDu+EZ/ad4GYxHpz7dZe+3y\n"
        + "AWtpytOv63bCQqBP9T9SUnzPcOy1NUbE5rHmDC3iZeRe/YECQQDvwWwCqoNcsyu1\n"
        + "Yo8oLwaFK6/q7dpW5ioHFEP68bvCCEJNDP8YCakpFw17jp26BjO7EEinyQGVLU+8\n"
        + "aM7y81exAkEAiVufcOzSDNKmf4CF9oHDWa9Qf1d83BpaJUw3IkZvn5yhjbdhBAyp\n"
        + "1S97MM2xH0wmuPCE9ECoGSaqV2REWtulBwJAS15oj+X+aZPBd8HF8wHlcNOs95NS\n"
        + "AeNU7ujF/gsj1Pk3rnUZc/sFfG9Mw7yvhJ0Sggm6bVRY9QZLfJUOCX3J4QJAJNmN\n"
        + "xs/h8kq5HE+woNdjPzZHVEJ2Xt46/PKbf/iBjcKJnOlrf5ieH3FjjU5BjHHzmX39\n"
        + "TUHjVwwGeveNVwrCFQJAEjoNNj5VRy4nVO5iBOubMDDOf0TYUuGhY3s/zMMRTTh2\n"
        + "sXPVYAsGD1wizrXX+wFaL3chtF1oG1Fx/jcsSsG6BA==\n"
        + "-----END RSA PRIVATE KEY-----\n")).isEqualTo(heldCertificate.privateKeyPkcs1Pem());

    assertThat((""
        + "-----BEGIN PRIVATE KEY-----\n"
        + "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAICkUeG2stqfbyr6\n"
        + "gyiVm5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z\n"
        + "1crhQlUzpKqncrzwqbzPuAyt2t9Oib/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgj\n"
        + "SYigTHTEWxCEgnrfu/YzEkO6l3rXAgMBAAECgYB99mhnB6piADOuddXv626NzUBT\n"
        + "r4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPbC8Di3sQSTnVGpSq\n"
        + "AvEXknBMzIc0UO74Rn9p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1\n"
        + "RsTmseYMLeJl5F79gQJBAO/BbAKqg1yzK7VijygvBoUrr+rt2lbmKgcUQ/rxu8II\n"
        + "Qk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT7xozvLzV7ECQQCJW59w7NIM0qZ/gIX2\n"
        + "gcNZr1B/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJqpXZERa\n"
        + "26UHAkBLXmiP5f5pk8F3wcXzAeVw06z3k1IB41Tu6MX+CyPU+TeudRlz+wV8b0zD\n"
        + "vK+EnRKCCbptVFj1Bkt8lQ4JfcnhAkAk2Y3Gz+HySrkcT7Cg12M/NkdUQnZe3jr8\n"
        + "8pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXDAZ6941XCsIVAkASOg02PlVH\n"
        + "LidU7mIE65swMM5/RNhS4aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgb\n"
        + "UXH+NyxKwboE\n"
        + "-----END PRIVATE KEY-----\n")).isEqualTo(heldCertificate.privateKeyPkcs8Pem());
  }

  @Test public void ecdsaSignedByRsa() {
    HeldCertificate root = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .rsa2048()
        .build();
    HeldCertificate leaf = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .signedBy(root)
        .build();

    assertThat(root.certificate().getSigAlgName()).isEqualTo("SHA256WITHRSA");
    assertThat(leaf.certificate().getSigAlgName()).isEqualTo("SHA256WITHRSA");
  }

  @Test public void rsaSignedByEcdsa() {
    HeldCertificate root = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .build();
    HeldCertificate leaf = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .rsa2048()
        .signedBy(root)
        .build();

    assertThat(root.certificate().getSigAlgName()).isEqualTo("SHA256WITHECDSA");
    assertThat(leaf.certificate().getSigAlgName()).isEqualTo("SHA256WITHECDSA");
  }

  @Test public void decodeEcdsa256() throws Exception {
    // The certificate + private key below was generated programmatically:
    //
    // HeldCertificate heldCertificate = new HeldCertificate.Builder()
    //     .validityInterval(5_000L, 10_000L)
    //     .addSubjectAlternativeName("1.1.1.1")
    //     .addSubjectAlternativeName("cash.app")
    //     .serialNumber(42L)
    //     .commonName("cash.app")
    //     .organizationalUnit("engineering")
    //     .ecdsa256()
    //     .build();

    String certificatePem = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl\n"
        + "cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx\n"
        + "MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h\n"
        + "cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD\n"
        + "ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw\n"
        + "HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF\n"
        + "AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT\n"
        + "yyaoEufLKVXhrTQhRfodTeigi4RX\n"
        + "-----END CERTIFICATE-----\n";
    String pkcs8Pem = ""
        + "-----BEGIN PRIVATE KEY-----\n"
        + "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J\n"
        + "lu/GJQZoU9lDrCPeUcQ28tzOWw==\n"
        + "-----END PRIVATE KEY-----\n";

    HeldCertificate heldCertificate = HeldCertificate.decode(certificatePem + pkcs8Pem);
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem);
    assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem);

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getNotBefore().getTime()).isEqualTo(5_000L);
    assertThat(certificate.getNotAfter().getTime()).isEqualTo(10_000L);

    assertThat(certificate.getSubjectAlternativeNames()).containsExactly(
        asList(GeneralName.iPAddress, "1.1.1.1"),
        asList(GeneralName.dNSName, "cash.app"));

    assertThat(certificate.getSubjectX500Principal().getName())
        .isEqualTo("CN=cash.app,OU=engineering");
  }

  @Test public void decodeRsa512() throws Exception {
    // The certificate + private key below was generated with OpenSSL. Never generate certificates
    // with MD5 or 512-bit RSA; that's insecure!
    //
    // openssl req \
    //   -x509 \
    //   -md5 \
    //   -nodes \
    //   -days 1 \
    //   -newkey rsa:512 \
    //   -keyout privateKey.key \
    //   -out certificate.crt

    String certificatePem = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIBFzCBwgIJAIVAqagcVN7/MA0GCSqGSIb3DQEBBAUAMBMxETAPBgNVBAMMCGNh\n"
        + "c2guYXBwMB4XDTE5MDkwNzAyMjg0NFoXDTE5MDkwODAyMjg0NFowEzERMA8GA1UE\n"
        + "AwwIY2FzaC5hcHAwXDANBgkqhkiG9w0BAQEFAANLADBIAkEA8qAeoubm4mBTD9/J\n"
        + "ujLQkfk/fuJt/T5pVQ1vUEqxfcMw0zYgszQ5C2MiIl7M6JkTRKU01q9hVFCR83wX\n"
        + "zIdrLQIDAQABMA0GCSqGSIb3DQEBBAUAA0EAO1UpwhrkW3Ho1nZK/taoUQOoqz/n\n"
        + "HFVMtyEkm5gBDgz8nJXwb3zbegclQyH+kVou02S8zC5WWzEtd0R8S0LsTA==\n"
        + "-----END CERTIFICATE-----\n";
    String pkcs8Pem = ""
        + "-----BEGIN PRIVATE KEY-----\n"
        + "MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEA8qAeoubm4mBTD9/J\n"
        + "ujLQkfk/fuJt/T5pVQ1vUEqxfcMw0zYgszQ5C2MiIl7M6JkTRKU01q9hVFCR83wX\n"
        + "zIdrLQIDAQABAkEA7dEA9o/5k77y68ZhRv9z7QEwucBcKzQ3rsSCbWMpYqg924F9\n"
        + "L8Z76kzSedSO2PN8mg6y/OLL+qBuTeUK/yiowQIhAP0cknFMbqeNX6uvj/S+V7in\n"
        + "bIhQkhcSdJjRw8fxMnJpAiEA9WTp9wzJpn+9etZo0jJ8wkM0+LTMNELo47Ctz7l1\n"
        + "kiUCIQCi34vslD5wWyzBEcwUtZdFH5dbcF1Rs3KMFA9jzfWkYQIgHtiWiFV1K5a3\n"
        + "DK/S8UkjYY/tIq4nVRJsD+LvlkLrwnkCIECcz4yF4HQgv+Tbzj/gGSBl1VIliTcB\n"
        + "Rc5RUQ0mZJQF\n"
        + "-----END PRIVATE KEY-----\n";

    HeldCertificate heldCertificate = HeldCertificate.decode(pkcs8Pem + certificatePem);
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem);
    assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem);

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getSubjectX500Principal().getName())
        .isEqualTo("CN=cash.app");
  }

  @Test public void decodeRsa2048() throws Exception {
    // The certificate + private key below was generated programmatically:
    //
    // HeldCertificate heldCertificate = new HeldCertificate.Builder()
    //     .validityInterval(5_000L, 10_000L)
    //     .addSubjectAlternativeName("1.1.1.1")
    //     .addSubjectAlternativeName("cash.app")
    //     .serialNumber(42L)
    //     .commonName("cash.app")
    //     .organizationalUnit("engineering")
    //     .rsa2048()
    //     .build();

    String certificatePem = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIC7TCCAdWgAwIBAgIBKjANBgkqhkiG9w0BAQsFADApMRQwEgYDVQQLEwtlbmdp\n"
        + "bmVlcmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAw\n"
        + "MTAxMDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2Fz\n"
        + "aC5hcHAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCaU+vrUPL0APGI\n"
        + "SXIuRX4xRrigXmGKx+GRPnWDWvGJwOm23Vpq/eZxQx6PbSUB1+QZzAwge20RpNAp\n"
        + "2lt5/qFtgUpEon2j06rd/0+ODqqVJX+6d3SpmF1fPfKUB6AOZbxEkaJpBSTavoTg\n"
        + "G2M/NMdjZjrcB3quNQcLg54mmI3HJm1zOd/8i2fZjvoiyVY30Inn2SmQsAotXw1u\n"
        + "aE/319bnR2sQlnkp6MJU0eLEtKyRif/IODvY+mtRYYdkFtoeT6qQPMIh+gF/H3to\n"
        + "5tjs3g59QC8k2TJDop4EFYUOwdrtnb8wUiBnLyURD1szASE2IO2Ftk1zaNOPKtrv\n"
        + "VeJuB/mpAgMBAAGjIDAeMBwGA1UdEQEB/wQSMBCHBAEBAQGCCGNhc2guYXBwMA0G\n"
        + "CSqGSIb3DQEBCwUAA4IBAQAPm7vfk+rxSucxxbFiimmFKBw+ymieLY/kznNh0lHJ\n"
        + "q15fsMYK7TTTt2FFqyfVXhhRZegLrkrGb3+4Dz1uNtcRrjT4qo+T/JOuZGxtBLbm\n"
        + "4/hkFSYavtd2FW+/CK7EnQKUyabgLOblb21IHOlcPwpSe6KkJjpwq0TV/ozzfk/q\n"
        + "kGRA7/Ubn5TMRYyHWnod2SS14+BkItcWN03Z7kvyMYrpNZpu6vQRYsqJJFMcmpGZ\n"
        + "sZQW31gO2arPmfNotkQdFdNL12c9YZKkJGhyK6NcpffD2l6O9NS5SRD5RnkvBxQw\n"
        + "fX5DamL8je/YKSLQ4wgUA/5iVKlCiJGQi6fYIJ0kxayO\n"
        + "-----END CERTIFICATE-----\n";
    String pkcs8Pem = ""
        + "-----BEGIN PRIVATE KEY-----\n"
        + "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQCaU+vrUPL0APGI\n"
        + "SXIuRX4xRrigXmGKx+GRPnWDWvGJwOm23Vpq/eZxQx6PbSUB1+QZzAwge20RpNAp\n"
        + "2lt5/qFtgUpEon2j06rd/0+ODqqVJX+6d3SpmF1fPfKUB6AOZbxEkaJpBSTavoTg\n"
        + "G2M/NMdjZjrcB3quNQcLg54mmI3HJm1zOd/8i2fZjvoiyVY30Inn2SmQsAotXw1u\n"
        + "aE/319bnR2sQlnkp6MJU0eLEtKyRif/IODvY+mtRYYdkFtoeT6qQPMIh+gF/H3to\n"
        + "5tjs3g59QC8k2TJDop4EFYUOwdrtnb8wUiBnLyURD1szASE2IO2Ftk1zaNOPKtrv\n"
        + "VeJuB/mpAgMBAAECggEAOlOXaYNZn1Cv+INRrR1EmVkSNEIXeX0bymohvbhka1zG\n"
        + "t/8myiMVsh7c8PYeM3kl034j4y7ixPVWW0sUoaHT3vArYo9LDtzTyj1REu6GGAJp\n"
        + "KM82/1X/jBx8jufm3SokIoIsMKbqC+ZPj+ep9dx7sxyTCE+nVSnjdL2Uyx+DDg3o\n"
        + "na237HTScWIi+tMv5QGEwqLHS2q+NZYfjgnSxNY8BRw4XZCcIZRko9niuB5gUjj/\n"
        + "y01HwvOCWuOMaSKZak1OdOaz3427/TkhYIqf6ft0ELF+ASRk3BLQA06pRt88H3u2\n"
        + "3vsHJsWr2rkCN0h9uDp2o50ZQ5fvlxqG0QIZmvkIkQKBgQDOHeZKvXO5IxQ+S8ed\n"
        + "09bC5SKiclCdW+Ry7N2x1MBfrxc4TTTTNaUN9Qdc6RXANG9bX2CJv0Dkh/0yH3z9\n"
        + "Bdq6YcoP6DFCX46jwhCKvxMX9h9PFLvY7l2VSe7NfboGzvYLCy8ErsGuio8u9MHZ\n"
        + "osX2ch6Gdhn1xUwLCw+T7rNwjQKBgQC/rWb0sWfgbKhEqV+u5oov+fFjooWmTQlQ\n"
        + "jcj+lMWUOkitnPmX9TsH5JDa8I89Y0gJGu7Lfg8XSH+4FCCfX3mSLYwVH5vAIvmr\n"
        + "TjMqRwSahQuTr/g+lx7alpcUHYv3z6b3WYIXFPPr3t7grWNJ14wMv9DnItWOg84H\n"
        + "LlxAvXXsjQKBgQCRPPhdignVVyaYjwVl7TPTuWoiVbMAbxQW91lwSZ4UzmfqQF0M\n"
        + "xyw7HYHGsmelPE2LcTWxWpb7cee0PgPwtwNdejLL6q1rO7JjKghF/EYUCFYff1iu\n"
        + "j6hZ3fLr0cAXtBYjygmjnxDTUMd8KvO9y7j644cm8GlyiUgAMBcWAolmsQKBgQCT\n"
        + "AJQTWfPGxM6QSi3d32VfwhsFROGnVzGrm/HofYTCV6jhraAmkKcDOKJ3p0LT286l\n"
        + "XQiC/FzqiGmbbaRPVlPQbiofESzMQIamgMTwyaKYNy1XyP9kUVYSYqfff4GXPqRY\n"
        + "00bYGPOxlC3utkuNmEgKhxnaCncqY5+hFkceR6+nCQKBgQC1Gonjhw0lYe43aHpp\n"
        + "nDJKv3FnyN3wxjsR2c9sWpDzHA6CMVhSeLoXCB9ishmrSE/CygNlTU1TEy63xN22\n"
        + "+dMHl5I/urMesjKKWiKZHdbWVIjJDv25r3jrN9VLr4q6AD9r1Su5G0o2j0N5ujVg\n"
        + "SzpFHp+ZzhL/SANa8EqlcF6ItQ==\n"
        + "-----END PRIVATE KEY-----\n";

    HeldCertificate heldCertificate = HeldCertificate.decode(pkcs8Pem + certificatePem);
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem);
    assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem);

    X509Certificate certificate = heldCertificate.certificate();
    assertThat(certificate.getNotBefore().getTime()).isEqualTo(5_000L);
    assertThat(certificate.getNotAfter().getTime()).isEqualTo(10_000L);

    assertThat(certificate.getSubjectAlternativeNames()).containsExactly(
        asList(GeneralName.iPAddress, "1.1.1.1"),
        asList(GeneralName.dNSName, "cash.app"));

    assertThat(certificate.getSubjectX500Principal().getName())
        .isEqualTo("CN=cash.app,OU=engineering");
  }

  @Test public void decodeWrongNumber() {
    String certificatePem = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl\n"
        + "cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx\n"
        + "MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h\n"
        + "cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD\n"
        + "ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw\n"
        + "HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF\n"
        + "AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT\n"
        + "yyaoEufLKVXhrTQhRfodTeigi4RX\n"
        + "-----END CERTIFICATE-----\n";
    String pkcs8Pem = ""
        + "-----BEGIN PRIVATE KEY-----\n"
        + "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J\n"
        + "lu/GJQZoU9lDrCPeUcQ28tzOWw==\n"
        + "-----END PRIVATE KEY-----\n";

    try {
      HeldCertificate.decode(certificatePem);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("string does not include a private key");
    }

    try {
      HeldCertificate.decode(pkcs8Pem);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("string does not include a certificate");
    }

    try {
      HeldCertificate.decode(certificatePem + pkcs8Pem + certificatePem);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("string includes multiple certificates");
    }

    try {
      HeldCertificate.decode(pkcs8Pem + certificatePem + pkcs8Pem);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("string includes multiple private keys");
    }
  }

  @Test public void decodeWrongType() {
    try {
      HeldCertificate.decode(""
          + "-----BEGIN CERTIFICATE-----\n"
          + "MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo\n"
          + "-----END CERTIFICATE-----\n"
          + "-----BEGIN RSA PRIVATE KEY-----\n"
          + "sXPVYAsGD1wizrXX+wFaL3chtF1oG1Fx/jcsSsG6BA==\n"
          + "-----END RSA PRIVATE KEY-----\n");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("unexpected type: RSA PRIVATE KEY");
    }
  }

  @Test public void decodeMalformed() {
    try {
      HeldCertificate.decode(""
          + "-----BEGIN CERTIFICATE-----\n"
          + "MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl\n"
          + "-----END CERTIFICATE-----\n"
          + "-----BEGIN PRIVATE KEY-----\n"
          + "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J\n"
          + "lu/GJQZoU9lDrCPeUcQ28tzOWw==\n"
          + "-----END PRIVATE KEY-----\n");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("failed to decode certificate");
    }
    try {
      HeldCertificate.decode(""
          + "-----BEGIN CERTIFICATE-----\n"
          + "MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl\n"
          + "cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx\n"
          + "MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h\n"
          + "cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD\n"
          + "ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw\n"
          + "HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF\n"
          + "AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT\n"
          + "yyaoEufLKVXhrTQhRfodTeigi4RX\n"
          + "-----END CERTIFICATE-----\n"
          + "-----BEGIN PRIVATE KEY-----\n"
          + "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J\n"
          + "-----END PRIVATE KEY-----\n");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("failed to decode private key");
    }
  }
}
