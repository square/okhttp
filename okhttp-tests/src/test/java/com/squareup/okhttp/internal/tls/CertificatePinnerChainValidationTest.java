/*
 * Copyright (C) 2016 Square, Inc.
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
package com.squareup.okhttp.internal.tls;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.CertificatePinner;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.HeldCertificate;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import com.squareup.okhttp.testing.RecordingHostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CertificatePinnerChainValidationTest {
  @Rule public final MockWebServer server = new MockWebServer();

  /** The pinner should pull the root certificate from the trust manager. */
  @Test public void pinRootNotPresentInChain() throws Exception {
    HeldCertificate rootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();
    HeldCertificate intermediateCa = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .ca(2)
        .serialNumber("2")
        .commonName("intermediate_ca")
        .build();
    HeldCertificate certificate = new HeldCertificate.Builder()
        .issuedBy(intermediateCa)
        .serialNumber("3")
        .commonName(server.getHostName())
        .build();
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add(server.getHostName(), CertificatePinner.pin(rootCa.certificate))
        .build();
    SSLContext clientContext = new SslContextBuilder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = new OkHttpClient()
        .setSslSocketFactory(clientContext.getSocketFactory())
        .setHostnameVerifier(new RecordingHostnameVerifier())
        .setCertificatePinner(certificatePinner);

    SSLContext serverSslContext = new SslContextBuilder()
        .certificateChain(certificate, intermediateCa)
        .build();
    server.useHttps(serverSslContext.getSocketFactory(), false);

    // The request should complete successfully.
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals("abc", response1.body().string());

    // Confirm that a second request also succeeds. This should detect caching problems.
    server.enqueue(new MockResponse()
        .setBody("def")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("def", response2.body().string());
  }

  /** The pinner should accept an intermediate from the server's chain. */
  @Test public void pinIntermediatePresentInChain() throws Exception {
    HeldCertificate rootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();
    HeldCertificate intermediateCa = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .ca(2)
        .serialNumber("2")
        .commonName("intermediate_ca")
        .build();
    HeldCertificate certificate = new HeldCertificate.Builder()
        .issuedBy(intermediateCa)
        .serialNumber("3")
        .commonName(server.getHostName())
        .build();
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add(server.getHostName(), CertificatePinner.pin(intermediateCa.certificate))
        .build();
    SSLContext clientContext = new SslContextBuilder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = new OkHttpClient()
        .setSslSocketFactory(clientContext.getSocketFactory())
        .setHostnameVerifier(new RecordingHostnameVerifier())
        .setCertificatePinner(certificatePinner);

    SSLContext serverSslContext = new SslContextBuilder()
        .certificateChain(certificate, intermediateCa)
        .build();
    server.useHttps(serverSslContext.getSocketFactory(), false);

    // The request should complete successfully.
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals("abc", response1.body().string());

    // Confirm that a second request also succeeds. This should detect caching problems.
    server.enqueue(new MockResponse()
        .setBody("def")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("def", response2.body().string());
  }

  @Test public void unrelatedPinnedLeafCertificateInChain() throws Exception {
    // Start with a trusted root CA certificate.
    HeldCertificate rootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();

    // Add a good intermediate CA, and have that issue a good certificate to localhost. Prepare an
    // SSL context for an HTTP client under attack. It includes the trusted CA and a pinned
    // certificate.
    HeldCertificate goodIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .ca(2)
        .serialNumber("2")
        .commonName("good_intermediate_ca")
        .build();
    HeldCertificate goodCertificate = new HeldCertificate.Builder()
        .issuedBy(goodIntermediateCa)
        .serialNumber("3")
        .commonName(server.getHostName())
        .build();
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add(server.getHostName(), CertificatePinner.pin(goodCertificate.certificate))
        .build();
    SSLContext clientContext = new SslContextBuilder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = new OkHttpClient()
        .setSslSocketFactory(clientContext.getSocketFactory())
        .setHostnameVerifier(new RecordingHostnameVerifier())
        .setCertificatePinner(certificatePinner);

    // Add a bad intermediate CA and have that issue a rogue certificate for localhost. Prepare
    // an SSL context for an attacking webserver. It includes both these rogue certificates plus the
    // trusted good certificate above. The attack is that by including the good certificate in the
    // chain, we may trick the certificate pinner into accepting the rouge certificate.
    HeldCertificate compromisedIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .ca(2)
        .serialNumber("4")
        .commonName("bad_intermediate_ca")
        .build();
    HeldCertificate rogueCertificate = new HeldCertificate.Builder()
        .serialNumber("5")
        .issuedBy(compromisedIntermediateCa)
        .commonName(server.getHostName())
        .build();
    SSLContext serverSslContext = new SslContextBuilder()
        .certificateChain(rogueCertificate, compromisedIntermediateCa, goodCertificate, rootCa)
        .build();
    server.useHttps(serverSslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    // Make a request from client to server. It should succeed certificate checks (unfortunately the
    // rogue CA is trusted) but it should fail certificate pinning.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request);
    try {
      call.execute();
      fail();
    } catch (SSLPeerUnverifiedException expected) {
      // Certificate pinning fails!
      String message = expected.getMessage();
      assertTrue(message, message.startsWith("Certificate pinning failure!"));
    }
  }

  @Test public void unrelatedPinnedIntermediateCertificateInChain() throws Exception {
    // Start with two root CA certificates, one is good and the other is compromised.
    HeldCertificate rootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();
    HeldCertificate compromisedRootCa = new HeldCertificate.Builder()
        .serialNumber("2")
        .ca(3)
        .commonName("compromised_root")
        .build();

    // Add a good intermediate CA, and have that issue a good certificate to localhost. Prepare an
    // SSL context for an HTTP client under attack. It includes the trusted CA and a pinned
    // certificate.
    HeldCertificate goodIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(rootCa)
        .ca(2)
        .serialNumber("3")
        .commonName("intermediate_ca")
        .build();
    CertificatePinner certificatePinner = new CertificatePinner.Builder()
        .add(server.getHostName(), CertificatePinner.pin(goodIntermediateCa.certificate))
        .build();
    SSLContext clientContext = new SslContextBuilder()
        .addTrustedCertificate(rootCa.certificate)
        .addTrustedCertificate(compromisedRootCa.certificate)
        .build();
    OkHttpClient client = new OkHttpClient()
        .setSslSocketFactory(clientContext.getSocketFactory())
        .setHostnameVerifier(new RecordingHostnameVerifier())
        .setCertificatePinner(certificatePinner);

    // The attacker compromises the root CA, issues an intermediate with the same common name
    // "intermediate_ca" as the good CA. This signs a rogue certificate for localhost. The server
    // serves the good CAs certificate in the chain, which means the certificate pinner sees a
    // different set of certificates than the SSL verifier.
    HeldCertificate compromisedIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(compromisedRootCa)
        .ca(2)
        .serialNumber("4")
        .commonName("intermediate_ca")
        .build();
    HeldCertificate rogueCertificate = new HeldCertificate.Builder()
        .serialNumber("5")
        .issuedBy(compromisedIntermediateCa)
        .commonName(server.getHostName())
        .build();
    SSLContext serverSslContext = new SslContextBuilder()
        .certificateChain(
            rogueCertificate, goodIntermediateCa, compromisedIntermediateCa, compromisedRootCa)
        .build();
    server.useHttps(serverSslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    // Make a request from client to server. It should succeed certificate checks (unfortunately the
    // rogue CA is trusted) but it should fail certificate pinning.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request);
    try {
      call.execute();
      fail();
    } catch (SSLHandshakeException expected) {
      // On Android, the handshake fails before the certificate pinner runs.
      String message = expected.getMessage();
      assertTrue(message, message.contains("Could not validate certificate"));
    } catch (SSLPeerUnverifiedException expected) {
      // On OpenJDK, the handshake succeeds but the certificate pinner fails.
      String message = expected.getMessage();
      assertTrue(message, message.startsWith("Certificate pinning failure!"));
    }
  }
}
