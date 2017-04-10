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

import java.io.IOException;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.security.auth.x500.X500Principal;
import okhttp3.Call;
import okhttp3.DelegatingSSLSocketFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ClientAuthTest {
  @Rule public final MockWebServer server = new MockWebServer();

  public enum ClientAuth {
    NONE, WANTS, NEEDS
  }

  private HeldCertificate serverRootCa;
  private HeldCertificate serverIntermediateCa;
  private HeldCertificate serverCert;
  private HeldCertificate clientRootCa;
  private HeldCertificate clientIntermediateCa;
  private HeldCertificate clientCert;

  @Before
  public void setUp() throws GeneralSecurityException {
    serverRootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(3)
        .commonName("root")
        .build();
    serverIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(serverRootCa)
        .ca(2)
        .serialNumber("2")
        .commonName("intermediate_ca")
        .build();

    serverCert = new HeldCertificate.Builder()
        .issuedBy(serverIntermediateCa)
        .serialNumber("3")
        .commonName(server.getHostName())
        .build();

    clientRootCa = new HeldCertificate.Builder()
        .serialNumber("1")
        .ca(13)
        .commonName("root")
        .build();
    clientIntermediateCa = new HeldCertificate.Builder()
        .issuedBy(serverRootCa)
        .ca(12)
        .serialNumber("2")
        .commonName("intermediate_ca")
        .build();

    clientCert = new HeldCertificate.Builder()
        .issuedBy(clientIntermediateCa)
        .serialNumber("4")
        .commonName("Jethro Willis")
        .build();
  }

  @Test public void clientAuthForWants() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.WANTS);

    server.useHttps(socketFactory, false);
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=localhost"), response.handshake().peerPrincipal());
    assertEquals(new X500Principal("CN=Jethro Willis"), response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void clientAuthForNeeds() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.NEEDS);

    server.useHttps(socketFactory, false);
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=localhost"), response.handshake().peerPrincipal());
    assertEquals(new X500Principal("CN=Jethro Willis"), response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void clientAuthSkippedForNone() throws Exception {
    OkHttpClient client = buildClient(clientCert, clientIntermediateCa);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.NONE);

    server.useHttps(socketFactory, false);
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=localhost"), response.handshake().peerPrincipal());
    assertEquals(null, response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void missingClientAuthSkippedForWantsOnly() throws Exception {
    OkHttpClient client = buildClient(null, clientIntermediateCa);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.WANTS);

    server.useHttps(socketFactory, false);
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals(new X500Principal("CN=localhost"), response.handshake().peerPrincipal());
    assertEquals(null, response.handshake().localPrincipal());
    assertEquals("abc", response.body().string());
  }

  @Test public void missingClientAuthFailsForNeeds() throws Exception {
    OkHttpClient client = buildClient(null, clientIntermediateCa);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.NEEDS);

    server.useHttps(socketFactory, false);

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());

    try {
      call.execute();
      fail();
    } catch (SSLHandshakeException expected) {
    } catch (SSLException expected) {
      // Conscrypt
      assertTrue(getPlatform().equals("conscrypt"));
    } catch (SocketException expected) {
      // JDK 9
      assertTrue(getPlatform().equals("jdk9"));
    }
  }

  @Test public void invalidClientAuthFails() throws Throwable {
    HeldCertificate clientCert2 = new HeldCertificate.Builder()
        .serialNumber("4")
        .commonName("Jethro Willis")
        .build();

    OkHttpClient client = buildClient(clientCert2);

    SSLSocketFactory socketFactory = buildServerSslSocketFactory(ClientAuth.NEEDS);

    server.useHttps(socketFactory, false);

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());

    try {
      call.execute();
      fail();
    } catch (SSLHandshakeException expected) {
    } catch (SSLException expected) {
      // Conscrypt
      assertTrue(getPlatform().equals("conscrypt"));
    } catch (SocketException expected) {
      // JDK 9
      assertTrue(getPlatform().equals("jdk9"));
    }
  }

  public OkHttpClient buildClient(HeldCertificate cert, HeldCertificate... chain) {
    SslClient.Builder sslClientBuilder = new SslClient.Builder()
        .addTrustedCertificate(serverRootCa.certificate);

    if (cert != null) {
      sslClientBuilder.certificateChain(cert, chain);
    }

    SslClient sslClient = sslClientBuilder.build();
    return defaultClient().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .build();
  }

  public SSLSocketFactory buildServerSslSocketFactory(final ClientAuth clientAuth) {
    SslClient serverSslClient = new SslClient.Builder()
        .addTrustedCertificate(serverRootCa.certificate)
        .addTrustedCertificate(clientRootCa.certificate)
        .certificateChain(serverCert, serverIntermediateCa)
        .build();

    return new DelegatingSSLSocketFactory(serverSslClient.socketFactory) {
      @Override protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
        if (clientAuth == ClientAuth.NEEDS) {
          sslSocket.setNeedClientAuth(true);
        } else if (clientAuth == ClientAuth.WANTS) {
          sslSocket.setWantClientAuth(true);
        }

        return super.configureSocket(sslSocket);
      }
    };
  }
}
