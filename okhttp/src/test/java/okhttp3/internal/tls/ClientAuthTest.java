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
import okhttp3.OkHttpClientTestRule;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.testing.PlatformRule;
import okhttp3.testing.PlatformVersion;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.testing.PlatformRule.getPlatformSystemProperty;
import static okhttp3.tls.internal.TlsUtil.newKeyManager;
import static okhttp3.tls.internal.TlsUtil.newTrustManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ClientAuthTest {
  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final MockWebServer server = new MockWebServer();

  private HeldCertificate serverRootCa;
  private HeldCertificate serverIntermediateCa;
  private HeldCertificate serverCert;
  private HeldCertificate clientRootCa;
  private HeldCertificate clientIntermediateCa;
  private HeldCertificate clientCert;

  @Before
  public void setUp() {
    platform.assumeNotOpenJSSE();

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
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isEqualTo(
        new X500Principal("CN=Jethro Willis"));
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void clientAuthForNeeds() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isEqualTo(
        new X500Principal("CN=Jethro Willis"));
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void clientAuthSkippedForNone() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.noClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isNull();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void missingClientAuthSkippedForWantsOnly() throws Exception {
    OkHttpClient client = buildClient(null, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requestClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isNull();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void missingClientAuthFailsForNeeds() throws Exception {
    // Fails with 11.0.1 https://github.com/square/okhttp/issues/4598
    // StreamReset stream was reset: PROT...

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
      assertThat(PlatformVersion.INSTANCE.getMajorVersion()).isGreaterThanOrEqualTo(11);
    } catch (SocketException expected) {
      assertThat(getPlatformSystemProperty()).isIn(PlatformRule.JDK9_PROPERTY,
          PlatformRule.CONSCRYPT_PROPERTY);
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
    // Fails with https://github.com/square/okhttp/issues/4598
    // StreamReset stream was reset: PROT...

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
      assertThat(PlatformVersion.INSTANCE.getMajorVersion()).isGreaterThanOrEqualTo(11);
    } catch (SocketException expected) {
      assertThat(getPlatformSystemProperty()).isIn(PlatformRule.JDK9_PROPERTY,
          PlatformRule.CONSCRYPT_PROPERTY);
    } catch (ConnectionShutdownException expected) {
      // It didn't fail until it reached the application layer.
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
    return clientTestRule.newClientBuilder()
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
          null, asList(serverRootCa.certificate(), clientRootCa.certificate()));
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(new KeyManager[] {keyManager}, new TrustManager[] {trustManager},
          new SecureRandom());
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }
}
