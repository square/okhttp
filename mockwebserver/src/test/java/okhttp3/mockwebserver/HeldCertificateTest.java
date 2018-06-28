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
package okhttp3.mockwebserver;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okio.ByteString;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeldCertificateTest {
  @Test public void defaultCertificate() throws CertificateParsingException {
    long now = System.currentTimeMillis();
    HeldCertificate heldCertificate = new HeldCertificate.Builder().build();

    X509Certificate certificate = heldCertificate.certificate();
    assertEquals("self-signed",
        certificate.getIssuerX500Principal().getName(),
        certificate.getSubjectX500Principal().getName());
    assertTrue(certificate.getIssuerX500Principal().getName().matches("CN=[0-9a-f-]{36}"));
    assertEquals(BigInteger.ONE, certificate.getSerialNumber());
    assertNull(certificate.getSubjectAlternativeNames());

    double deltaMillis = 1000.0;
    long durationMillis = TimeUnit.MINUTES.toMillis(60 * 24);
    assertEquals((double) now, certificate.getNotBefore().getTime(), deltaMillis);
    assertEquals((double) now + durationMillis, certificate.getNotAfter().getTime(), deltaMillis);

    System.out.println(ByteString.of(heldCertificate.keyPair().getPublic().getEncoded()).base64());
    System.out.println(ByteString.of(heldCertificate.keyPair().getPrivate().getEncoded()).base64());
  }

  @Test public void customInterval() {
    // 5 seconds starting on 1970-01-01.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .validityInterval(5_000L, 10_000L)
        .build();
    X509Certificate certificate = heldCertificate.certificate();
    assertEquals(5_000L, certificate.getNotBefore().getTime());
    assertEquals(10_000L, certificate.getNotAfter().getTime());
  }

  @Test public void customDuration() {
    long now = System.currentTimeMillis();

    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .duration(5, TimeUnit.SECONDS)
        .build();
    X509Certificate certificate = heldCertificate.certificate();

    double deltaMillis = 1000.0;
    long durationMillis = 5_000L;
    assertEquals((double) now, certificate.getNotBefore().getTime(), deltaMillis);
    assertEquals((double) now + durationMillis, certificate.getNotAfter().getTime(), deltaMillis);
  }

  @Test public void subjectAlternativeNames() throws CertificateParsingException {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .addSubjectAlternativeName("1.1.1.1")
        .addSubjectAlternativeName("cash.app")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    List<List<?>> subjectAlternativeNames = new ArrayList<>(
        certificate.getSubjectAlternativeNames());
    assertEquals(subjectAlternativeNames, Arrays.asList(
        Arrays.asList(GeneralName.iPAddress, "1.1.1.1"),
        Arrays.asList(GeneralName.dNSName, "cash.app")));
  }

  @Test public void commonName() {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("cash.app")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    assertEquals("CN=cash.app", certificate.getSubjectX500Principal().getName());
  }

  @Test public void organizationalUnit() {
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("cash.app")
        .organizationalUnit("cash")
        .build();

    X509Certificate certificate = heldCertificate.certificate();
    assertEquals("CN=cash.app,OU=cash", certificate.getSubjectX500Principal().getName());
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
        .build();

    assertEquals(heldCertificate.certificatePem(), ""
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
        + "-----END CERTIFICATE-----\n");

    assertEquals(heldCertificate.privateKeyPkcs1Pem(), ""
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
        + "-----END RSA PRIVATE KEY-----\n");

    assertEquals(heldCertificate.privateKeyPkcs8Pem(), ""
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
        + "-----END PRIVATE KEY-----\n");
  }
}
