/*
 * Copyright (C) 2020 Square, Inc.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static okhttp3.CertificatePinner.sha256Hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Let's Encrypt cross signing test.
 * <p>
 * https://letsencrypt.org/certificates/ https://scotthelme.co.uk/cross-signing-alternate-trust-paths-how-they-work/
 */
@Execution(ExecutionMode.CONCURRENT)
@Tag("Remote")
@Disabled("Don't run by default as it's basically undefined.")
public class CrossSigningTest {
  private X509Certificate validisrgrootx1;
  private X509Certificate isrgrootx1;
  private X509Certificate letsencryptauthorityx3;
  private X509Certificate trustidx3root;
  private X509Certificate letsencryptx3crosssigned;

  private final OkHttpClient cleanClient = new OkHttpClient();
  private List<X509Certificate> peerCertificates;

  @BeforeEach
  public void setup() throws CertificateException, IOException {
    validisrgrootx1 = toCert("-----BEGIN CERTIFICATE-----\n"
        + "MIIFeDCCBGCgAwIBAgISA00GltSI+wZA2ESjiBZW8SKYMA0GCSqGSIb3DQEBCwUA\n"
        + "MEoxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBFbmNyeXB0MSMwIQYDVQQD\n"
        + "ExpMZXQncyBFbmNyeXB0IEF1dGhvcml0eSBYMzAeFw0yMDEwMTQxNTAwNTBaFw0y\n"
        + "MTAxMTIxNTAwNTBaMCsxKTAnBgNVBAMTIHZhbGlkLWlzcmdyb290eDEubGV0c2Vu\n"
        + "Y3J5cHQub3JnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAseBN6VVJ\n"
        + "DCTSbTZYTZT66PYvwu0ik5Mw+vmAqgKjnnynOQPNlCKY1fGZVZ85OFLObQSbxLAj\n"
        + "9EIP7eOBQnUDlH6PQAoz0iYQqR+O5vAfez17BMEbwcHJIQrf+wqlI04iOJU33m/t\n"
        + "jo05UVoyxKKmoLj8FtirJnw4KnkyFR+ppnwSPVKQIS9wRsTd5b8IW3v2ml9L5/c3\n"
        + "mxcFpvfqvbf3szOvRnttrVDaRvPZAoovFRR5Xf6XwZUiy6/5mxd27EOku8dFUgjk\n"
        + "65ReQNfgxcFfvGUB1vMwtO90u7M7Udo65S0YoNC995tIp3kT/gSfQldCRjuc3yb7\n"
        + "szXeALnK4GFvvwIDAQABo4ICdTCCAnEwDgYDVR0PAQH/BAQDAgWgMB0GA1UdJQQW\n"
        + "MBQGCCsGAQUFBwMBBggrBgEFBQcDAjAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBTD\n"
        + "iR66DzqVRlIV/cY3RXIUWxVbezAfBgNVHSMEGDAWgBSoSmpjBH3duubRObemRWXv\n"
        + "86jsoTBvBggrBgEFBQcBAQRjMGEwLgYIKwYBBQUHMAGGImh0dHA6Ly9vY3NwLmlu\n"
        + "dC14My5sZXRzZW5jcnlwdC5vcmcwLwYIKwYBBQUHMAKGI2h0dHA6Ly9jZXJ0Lmlu\n"
        + "dC14My5sZXRzZW5jcnlwdC5vcmcvMCsGA1UdEQQkMCKCIHZhbGlkLWlzcmdyb290\n"
        + "eDEubGV0c2VuY3J5cHQub3JnMEwGA1UdIARFMEMwCAYGZ4EMAQIBMDcGCysGAQQB\n"
        + "gt8TAQEBMCgwJgYIKwYBBQUHAgEWGmh0dHA6Ly9jcHMubGV0c2VuY3J5cHQub3Jn\n"
        + "MIIBBAYKKwYBBAHWeQIEAgSB9QSB8gDwAHYAlCC8Ho7VjWyIcx+CiyIsDdHaTV5s\n"
        + "T5Q9YdtOL1hNosIAAAF1J9e4KAAABAMARzBFAiEAiXPLTVJ5b0hmy6ymIANAwAnX\n"
        + "Ham0XP2/3chFAq4Nh3ICIHUwyFu88CF4Xv0NeXbAGlZzWeUVrOxgHNJkNgIvNn/t\n"
        + "AHYA9lyUL9F3MCIUVBgIMJRWjuNNExkzv98MLyALzE7xZOMAAAF1J9e4PwAABAMA\n"
        + "RzBFAiA/rVA0Qqjj+tyCuRaRvGQlb2TQPPuJFHW6sAdX8V4SNwIhAN31Pp7D6WeD\n"
        + "nfxnXjMpwcJFsZd9Tv/unmnrzxbcrDRqMA0GCSqGSIb3DQEBCwUAA4IBAQBLWEWj\n"
        + "8b8uKc70FN16FX3N5WYp0rQwoiMG4jjmtv7fR9rozlmXPF1UCaQz2DlhhggSmQos\n"
        + "7VXpaHQD3edmKpkZCoAsw4fc4VFXG4vp1XPMMcML/FNdLK+MJctsbHHaQlP0RJgF\n"
        + "A7Aogp4NkxKPFJUYeTEtT9LsI0Y7F8U9Da0hgPFH8m4x722pTfoOPWsAJd1VhguD\n"
        + "mBIQ6B/RTWMM7mxHvbmT2aoIaBM5ZSP3DDJmIpj3eMx50jQjqchQUlCWzKq+7eeq\n"
        + "YYwy4x9evYr/EbCmekyJJ/LZBZi42fIAtXson+TTMpjNPkzTgkHxZG8AVNS7Lj0u\n"
        + "66gAhzRTHlROnp6i\n"
        + "-----END CERTIFICATE-----\n");
    isrgrootx1 = readCert("https://letsencrypt.org/certs/isrgrootx1.pem");
    letsencryptauthorityx3 = readCert("https://letsencrypt.org/certs/letsencryptauthorityx3.pem");
    trustidx3root = readCert("https://letsencrypt.org/certs/trustid-x3-root.pem.txt");
    letsencryptx3crosssigned =
        readCert("https://letsencrypt.org/certs/lets-encrypt-x3-cross-signed.pem");
  }

  @NotNull private String pin(X509Certificate cert) {
    return "sha256/" + sha256Hash(cert).base64();
  }

  private X509Certificate readCert(String pemUrl) throws CertificateException, IOException {
    String pem = fetch(cleanClient, pemUrl);
    return toCert(pem);
  }

  private X509Certificate toCert(String pem) throws CertificateException {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    return (X509Certificate) cf.generateCertificate(
        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
  }

  @Test public void testPins() throws IOException, CertificateException {
    assertEquals("sha256/SJqhYQhQqJxyAhe52dvcf4CRgRnzK4jC3TvPrx3ikHk=", pin(validisrgrootx1));
    assertEquals("sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", pin(isrgrootx1));
    assertEquals("sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=", pin(letsencryptauthorityx3));
    assertEquals("sha256/Vjs8r4z+80wjNcr1YKepWQboSIRi63WsWXhIMN+eWys=", pin(trustidx3root));
    assertEquals("sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=", pin(letsencryptx3crosssigned));
  }

  /**
   * Ideal simple case, we have new ISRG and DST certificate in root CA and everything works
   * as expected. ISRG Root is used in peer certs.
   */
  @Test public void passesWithBothRoots() throws IOException, CertificateException {
    OkHttpClient client = buildClient(asList(isrgrootx1, trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptauthorityx3, isrgrootx1),
        peerCertificates);
  }

  /**
   * Ideal simple case, we have new ISRG certificate in root CA and everything works
   * as expected.
   */
  @Test public void passesWithIsrgRoot() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(isrgrootx1));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptauthorityx3, isrgrootx1),
        peerCertificates);
  }

  /**
   * Unfortunate DST alternate case, we have new DST certificate in root CA and fails
   * because web server delivers ISRG path.
   */
  @Test public void failsWithDstRoot() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", true);
  }

  /**
   * Awkward intermediate case, we have new X3 certificate in root CA and shortened
   * peer certificates (2).
   */
  @Test public void passesWithX3() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(letsencryptauthorityx3));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptauthorityx3),
        peerCertificates);
  }

  /**
   * Awkward intermediate case, we have new X3 certificate cross signed in root CA and get shortened
   * peer certificates (2).
   */
  @Test public void passesWithX3CrossSigned() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(letsencryptx3crosssigned));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    // check on complete certificates because name match is not enough
    assertCertsEquals(asList(validisrgrootx1, letsencryptx3crosssigned),
        peerCertificates);
  }

  /**
   * Ideal but strange case, we have new ISRG and DST certificate and both intermediate forms
   * in root CA and everything works as expected. Surprisingly DST Root is used in peer certs
   * and retained. This doesn't even logically make sense as the served peer certificates
   * differ.
   */
  @Test public void passesWithAll() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptx3crosssigned, trustidx3root),
        peerCertificates);
  }

  /**
   * We have new ISRG and DST certificate and both intermediate forms in root CA pinning
   * causes failures because we don't predict the right one.
   */
  @Test public void failsWithAllPinnedIsrg() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned),
        singletonList(isrgrootx1));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", true);
  }

  /**
   * We have new ISRG and DST certificate and both intermediate forms in root CA, pinning
   * passes because we predict the right one.
   */
  @Test public void passesWithAllPinnedDst() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned),
        singletonList(trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptx3crosssigned, trustidx3root),
        peerCertificates);
  }

  /**
   * We have new ISRG and DST certificate and both intermediate forms in root CA, pinning
   * passes because we try all.
   */
  @Test public void passesWithAllPinnedBoth() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned),
        asList(isrgrootx1, trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptx3crosssigned, trustidx3root),
        peerCertificates);
  }

  /**
   * We have new ISRG and DST certificate in root CA, pinning passes because ISRG is only
   * valid path to root through intermediate.
   */
  @Test public void passesWithBootRootsPinnedIsrg() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), singletonList(isrgrootx1));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptauthorityx3, isrgrootx1),
        peerCertificates);
  }

  /**
   * We have new ISRG and DST certificate in root CA, pinning fails acceptably because ISRG is only
   * valid path to root through intermediate and we pinned to DST.
   */
  @Test public void failsWithBootRootsPinnedDst() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), singletonList(trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", true);
  }

  /**
   * We have new ISRG and DST certificate in root CA, pinning passes because we tried both.
   */
  @Test public void passesWithBootRootsPinnedBoth() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), asList(isrgrootx1, trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);

    assertCertsEquals(asList(validisrgrootx1, letsencryptauthorityx3, isrgrootx1),
        peerCertificates);
  }

  private void assertCertsEquals(List<X509Certificate> expected, List<X509Certificate> actual) {
    assertEquals(names(expected), names(actual));
    assertEquals(expected, actual);
  }

  private List<String> names(List<X509Certificate> certificates) {
    return certificates.stream().map(c -> c.getSubjectX500Principal().getName()).collect(toList());
  }

  private OkHttpClient buildClient(List<X509Certificate> certs, List<X509Certificate> pinnedCerts) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();

    HandshakeCertificates.Builder certificateBuilder = new HandshakeCertificates.Builder();
    for (X509Certificate c : certs) {
      certificateBuilder.addTrustedCertificate(c);
    }
    HandshakeCertificates certificates = certificateBuilder.build();

    builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager());

    if (!pinnedCerts.isEmpty()) {
      CertificatePinner.Builder pinner = new CertificatePinner.Builder();

      for (X509Certificate cert : pinnedCerts) {
        pinner.add("*.letsencrypt.org", pin(cert));
      }

      builder.certificatePinner(pinner.build());
    }

    OkHttpClient client = builder.build();
    return client;
  }

  @NotNull private OkHttpClient buildClient(List<X509Certificate> certs) {
    return buildClient(certs, emptyList());
  }

  private void checkRequest(OkHttpClient client, String host, boolean expectFailure)
      throws IOException {
    try {
      sendRequest(client, "https://" + host + "/robots.txt");
      if (expectFailure) {
        fail();
      }
    } catch (SSLPeerUnverifiedException spue) {
      assertTrue(expectFailure);
    } catch (SSLHandshakeException sslhe) {
      assertTrue(expectFailure);
    }
  }

  private void sendRequest(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder()
        .url(url)
        .build();
    try (Response response = client.newCall(request).execute()) {
      assertTrue(response.code() == 200 || response.code() == 404);
      peerCertificates = (List<X509Certificate>) response.handshake()
          .peerCertificates()
          .stream()
          .map(c -> (X509Certificate) c)
          .collect(toList());
    }
  }

  private String fetch(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder()
        .url(url)
        .build();
    try (Response response = client.newCall(request).execute()) {
      assertTrue(response.code() == 200 || response.code() == 404);
      return response.body().string();
    }
  }
}
