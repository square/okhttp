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
package okhttp3.internal.tls;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.http2.Http2;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static okhttp3.internal.platform.PlatformTest.getPlatform;
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
    SslClient sslClient = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = defaultClient().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build();

    SslClient serverSslClient = new SslClient.Builder()
        .certificateChain(certificate, intermediateCa)
        .build();
    server.useHttps(serverSslClient.socketFactory, false);

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
    Logger frameLogger = Logger.getLogger(Http2.class.getName());
    frameLogger.setLevel(Level.FINE);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.FINE);
    handler.setFormatter(new SimpleFormatter() {
      @Override public String format(LogRecord record) {
        return Util.format("%s%n", record.getMessage());
      }
    });
    frameLogger.addHandler(handler);
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
    SslClient contextBuilder = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = defaultClient().newBuilder()
        .sslSocketFactory(contextBuilder.socketFactory, contextBuilder.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build();

    SslClient serverSslContext = new SslClient.Builder()
        .certificateChain(certificate.keyPair, certificate.certificate, intermediateCa.certificate)
        .build();
    server.useHttps(serverSslContext.socketFactory, false);

    // The request should complete successfully.
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals("abc", response1.body().string());
    response1.close();

    // Force a fresh connection for the next request.
    client.connectionPool().evictAll();

    Thread.sleep(250);

    // Confirm that a second request also succeeds. This should detect caching problems.
    server.enqueue(new MockResponse()
        .setBody("def")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("def", response2.body().string());
    response2.close();
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
    SslClient clientContextBuilder = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build();
    OkHttpClient client = defaultClient().newBuilder()
        .sslSocketFactory(clientContextBuilder.socketFactory, clientContextBuilder.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build();

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

    SslClient.Builder sslBuilder = new SslClient.Builder();

    // Test setup fails on JDK9
    // java.security.KeyStoreException: Certificate chain is not valid
    // at sun.security.pkcs12.PKCS12KeyStore.setKeyEntry
    // http://openjdk.java.net/jeps/229
    // http://hg.openjdk.java.net/jdk9/jdk9/jdk/file/2c1c21d11e58/src/share/classes/sun/security/pkcs12/PKCS12KeyStore.java#l596
    if (getPlatform().equals("jdk9")) {
      sslBuilder.keyStoreType("JKS");
    }

    SslClient serverSslContext = sslBuilder.certificateChain(
        rogueCertificate.keyPair, rogueCertificate.certificate, compromisedIntermediateCa.certificate, goodCertificate.certificate, rootCa.certificate)
        .build();
    server.useHttps(serverSslContext.socketFactory, false);
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
    SslClient clientContextBuilder = new SslClient.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .addTrustedCertificate(compromisedRootCa.certificate)
        .build();
    OkHttpClient client = defaultClient().newBuilder()
        .sslSocketFactory(clientContextBuilder.socketFactory, clientContextBuilder.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build();

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

    SslClient.Builder sslBuilder = new SslClient.Builder();

    // Test setup fails on JDK9
    // java.security.KeyStoreException: Certificate chain is not valid
    // at sun.security.pkcs12.PKCS12KeyStore.setKeyEntry
    // http://openjdk.java.net/jeps/229
    // http://hg.openjdk.java.net/jdk9/jdk9/jdk/file/2c1c21d11e58/src/share/classes/sun/security/pkcs12/PKCS12KeyStore.java#l596
    if (getPlatform().equals("jdk9")) {
      sslBuilder.keyStoreType("JKS");
    }

    SslClient serverSslContext = sslBuilder.certificateChain(
            rogueCertificate.keyPair, rogueCertificate.certificate, goodIntermediateCa.certificate, compromisedIntermediateCa.certificate, compromisedRootCa.certificate)
        .build();
    server.useHttps(serverSslContext.socketFactory, false);
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
