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
package okhttp.regression;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Let's Encrypt expiring root test.
 *
 * Read https://community.letsencrypt.org/t/mobile-client-workarounds-for-isrg-issue/137807
 * for background.
 */
@RunWith(AndroidJUnit4.class)
public class LetsEncryptTest {
  @Test public void getFailsWithoutAdditionalCert() throws IOException {
    OkHttpClient client = new OkHttpClient();

    boolean androidMorEarlier = Build.VERSION.SDK_INT <= 23;
    try {
      sendRequest(client, "https://valid-isrgrootx1.letsencrypt.org/robots.txt");
      if (androidMorEarlier) {
        fail();
      }
    } catch (SSLHandshakeException sslhe) {
      assertTrue(androidMorEarlier);
    }
  }

  @Test public void getPassesAdditionalCert() throws IOException, CertificateException {
    boolean androidMorEarlier = Build.VERSION.SDK_INT <= 23;

    OkHttpClient.Builder builder = new OkHttpClient.Builder();

    if (androidMorEarlier) {
      String isgCert =
              "-----BEGIN CERTIFICATE-----\n" +
              "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
              "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
              "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
              "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
              "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
              "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
              "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n" +
              "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n" +
              "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n" +
              "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n" +
              "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n" +
              "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n" +
              "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n" +
              "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n" +
              "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n" +
              "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n" +
              "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n" +
              "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n" +
              "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n" +
              "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n" +
              "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n" +
              "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n" +
              "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n" +
              "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n" +
              "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n" +
              "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n" +
              "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n" +
              "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n" +
              "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n" +
              "-----END CERTIFICATE-----";

      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      Certificate isgCertificate = cf.generateCertificate(new ByteArrayInputStream(isgCert.getBytes("UTF-8")));

      HandshakeCertificates certificates = new HandshakeCertificates.Builder()
              .addTrustedCertificate((X509Certificate) isgCertificate)
              // Uncomment to allow connection to any site generally, but will cause
              // noticeable memory pressure in Android apps.
//              .addPlatformTrustedCertificates()
              .build();

      builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager());
    }

    OkHttpClient client = builder.build();

    sendRequest(client, "https://valid-isrgrootx1.letsencrypt.org/robots.txt");

    try {
      sendRequest(client, "https://google.com/robots.txt");
      if (androidMorEarlier) {
        // will pass with default CAs on N or later
        fail();
      }
    } catch (SSLHandshakeException sslhe) {
      assertTrue(androidMorEarlier);
    }
  }

  private void sendRequest(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder()
            .url(url)
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertTrue(response.code() == 200 || response.code() == 404);
      assertEquals(Protocol.HTTP_2, response.protocol());
    }
  }
}
