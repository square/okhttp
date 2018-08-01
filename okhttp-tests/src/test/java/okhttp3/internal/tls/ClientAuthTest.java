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

import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HeldCertificate;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static okhttp3.tls.internal.TlsUtil.newKeyManager;
import static okhttp3.tls.internal.TlsUtil.newTrustManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ClientAuthTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private HeldCertificate serverRootCa;
  private HeldCertificate serverIntermediateCa;
  private HeldCertificate serverCert;
  private HeldCertificate clientRootCa;
  private HeldCertificate clientIntermediateCa;
  private HeldCertificate clientCert;

  @Before
  public void setUp() {
    serverRootCa = new HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build();
    serverIntermediateCa = new HeldCertificate.Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build();

    serverCert = new HeldCertificate.Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName("Local Host")
        .addSubjectAlternativeName(server.getHostName())
        .build();

    clientRootCa = new HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build();
    clientIntermediateCa = new HeldCertificate.Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build();

    clientCert = new HeldCertificate.Builder()
        .signedBy(clientIntermediateCa)
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .addSubjectAlternativeName("jethrowillis.com")
        .build();
  }

  @Test public void clientAuthForWants() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requestClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=Local Host"), response.handshake().peerPrincipal());
    assertEquals(new X500Principal("CN=Jethro Willis"), response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void clientAuthForNeeds() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=Local Host"), response.handshake().peerPrincipal());
    assertEquals(new X500Principal("CN=Jethro Willis"), response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void clientAuthSkippedForNone() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.noClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=Local Host"), response.handshake().peerPrincipal());
    assertEquals(null, response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void missingClientAuthSkippedForWantsOnly() throws Exception {
    OkHttpClient client = buildClient(null, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requestClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=Local Host"), response.handshake().peerPrincipal());
    assertEquals(null, response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void missingClientAuthFailsForNeeds() throws Exception {
    OkHttpClient client = buildClient(null, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());

    try {
      call.execute();
      fail();
    } catch (SSLHandshakeException expected) {
    } catch (SSLException expected) {
      String jvmVersion = System.getProperty("java.specification.version");
      assertEquals("11", jvmVersion);
    } catch (SocketException expected) {
      assertEquals("jdk9", getPlatform());
    }
  }

  @Test public void commonNameIsNotTrusted() throws Exception {
    serverCert = new HeldCertificate.Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName(server.getHostName())
        .addSubjectAlternativeName("different-host.com")
        .build();

    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());

    try {
      call.execute();
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  @Test public void invalidClientAuthFails() throws Throwable {
    HeldCertificate clientCert2 = new HeldCertificate.Builder()
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .build();

    OkHttpClient client = buildClient(clientCert2);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());

    try {
      call.execute();
      fail();
    } catch (SSLHandshakeException expected) {
    } catch (SSLException expected) {
      // javax.net.ssl.SSLException: readRecord
      String jvmVersion = System.getProperty("java.specification.version");
      assertEquals("11", jvmVersion);
    } catch (SocketException expected) {
      assertEquals("jdk9", getPlatform());
    }
  }

  private OkHttpClient buildClient(
      HeldCertificate heldCertificate, X509Certificate... intermediates) {
    HandshakeCertificates.Builder builder = new HandshakeCertificates.Builder()
        .addTrustedCertificate(serverRootCa.certificate());

    if (heldCertificate != null) {
      builder.heldCertificate(heldCertificate, intermediates);
    }

    HandshakeCertificates handshakeCertificates = builder.build();
    return defaultClient().newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .build();
  }

  private SSLSocketFactory buildServerSslSocketFactory() {
    // The test uses JDK default SSL Context instead of the Platform provided one
    // as Conscrypt seems to have some differences, we only want to test client side here.
    try {
      X509KeyManager keyManager = newKeyManager(
          null, serverCert, serverIntermediateCa.certificate());
      X509TrustManager trustManager = newTrustManager(
          null, Arrays.asList(serverRootCa.certificate(), clientRootCa.certificate()));
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(new KeyManager[] {keyManager}, new TrustManager[] {trustManager},
          new SecureRandom());
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }
}
