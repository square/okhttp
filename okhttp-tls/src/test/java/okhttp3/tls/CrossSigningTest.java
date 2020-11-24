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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static okhttp3.CertificatePinner.sha256Hash;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Let's Encrypt cross signing test.
 * <p>
 * https://letsencrypt.org/certificates/ https://scotthelme.co.uk/cross-signing-alternate-trust-paths-how-they-work/
 */
public class CrossSigningTest {
  private X509Certificate isrgrootx1;
  private X509Certificate letsencryptauthorityx3;
  private X509Certificate trustidx3root;
  private X509Certificate letsencryptx3crosssigned;
  private String isrgrootx1Pin;
  private String letsencryptauthorityx3Pin;
  private String trustidx3rootPin;
  private String letsencryptx3crosssignedPin;

  private OkHttpClient cleanClient = new OkHttpClient();

  @BeforeEach
  public void setup() throws CertificateException, IOException {
    isrgrootx1 = readCert("https://letsencrypt.org/certs/isrgrootx1.pem");
    letsencryptauthorityx3 = readCert("https://letsencrypt.org/certs/letsencryptauthorityx3.pem");
    trustidx3root = readCert("https://letsencrypt.org/certs/trustid-x3-root.pem.txt");
    letsencryptx3crosssigned =
        readCert("https://letsencrypt.org/certs/lets-encrypt-x3-cross-signed.pem");

    isrgrootx1Pin = "sha256/" + sha256Hash(isrgrootx1).base64();
    letsencryptauthorityx3Pin = "sha256/" + sha256Hash(letsencryptauthorityx3).base64();
    trustidx3rootPin = "sha256/" + sha256Hash(trustidx3root).base64();
    letsencryptx3crosssignedPin = "sha256/" + sha256Hash(letsencryptx3crosssigned).base64();
  }

  private X509Certificate readCert(String pemUrl) throws CertificateException, IOException {
    String pem = fetch(cleanClient, pemUrl);
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    return (X509Certificate) cf.generateCertificate(
        new ByteArrayInputStream(pem.getBytes("UTF-8")));
  }

  @Test public void passesWithBothRoots() throws IOException, CertificateException {
    OkHttpClient client = buildClient(asList(isrgrootx1, trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithIsrgRoot() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(isrgrootx1));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void failsWithDstRoot() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(trustidx3root));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", true);
  }

  @Test public void passesWithX3() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(letsencryptauthorityx3));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithX3CrossSigned() throws IOException, CertificateException {
    OkHttpClient client = buildClient(singletonList(letsencryptx3crosssigned));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithAll() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithAllPinnedIsrg() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned), asList(isrgrootx1Pin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithAllPinnedDst() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned), asList(trustidx3rootPin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithAllPinnedBoth() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root, letsencryptauthorityx3, letsencryptx3crosssigned), asList(isrgrootx1Pin, trustidx3rootPin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithBootRootsPinnedIsrg() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), asList(isrgrootx1Pin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithBootRootsPinnedDst() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), asList(trustidx3rootPin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  @Test public void passesWithBootRootsPinnedBoth() throws IOException, CertificateException {
    OkHttpClient client = buildClient(
        asList(isrgrootx1, trustidx3root), asList(isrgrootx1Pin, trustidx3rootPin));

    checkRequest(client, "valid-isrgrootx1.letsencrypt.org", false);
  }

  private OkHttpClient buildClient(List<X509Certificate> certs, List<String> pins) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();

    HandshakeCertificates.Builder certificateBuilder = new HandshakeCertificates.Builder();
    for (X509Certificate c : certs) {
      certificateBuilder.addTrustedCertificate(c);
    }
    HandshakeCertificates certificates = certificateBuilder.build();

    builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager());

    if (!pins.isEmpty()) {
      CertificatePinner.Builder pinner = new CertificatePinner.Builder();

      for (String pin: pins) {
        pinner.add("*.letsencrypt.org", pin);
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
      Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
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
