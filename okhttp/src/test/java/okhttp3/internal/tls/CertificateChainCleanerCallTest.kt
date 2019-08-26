/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.tls

import okhttp3.OkHttpClientTestRule
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import java.security.cert.Certificate
import java.security.cert.X509Certificate

class CertificateChainCleanerCallTest {
  @JvmField @Rule val platform = PlatformRule()
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  @JvmField @Rule val server = MockWebServer()

  /** The pinner should accept an intermediate from the server's chain.  */
  @Test @Throws(Exception::class)
  fun pinIntermediatePresentInChain() {
    val rootCa = HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .build()
    val intermediateCa = HeldCertificate.Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .build()
    val certificate = HeldCertificate.Builder()
        .signedBy(intermediateCa)
        .serialNumber(3L)
        .commonName(server.hostName)
        .build()
    val additional = HeldCertificate.Builder()
        .signedBy(intermediateCa)
        .serialNumber(4L)
        .commonName("additional")
        .build()
    val handshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
    val client = clientTestRule.newClientBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .hostnameVerifier(RecordingHostnameVerifier())
        .certificateChainCleaner(object : CertificateChainCleaner() {
          override fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
            // Pollute the certificate chain instead of cleaning
            return chain + additional.certificate
          }
        })
        .build()

    val serverHandshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate, intermediateCa.certificate)
        .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory(), false)

    // The request should complete successfully.
    server.enqueue(MockResponse()
        .setBody("abc")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
    val call1 = client.newCall(Request.Builder()
        .url(server.url("/"))
        .build())
    val response1 = call1.execute()
    assertThat(response1.body!!.string()).isEqualTo("abc")
    // Ensure we are using a Fake Certificate Cleaner
    assertThat(
        response1.handshake?.peerCertificates?.map { (it as X509Certificate).subjectDN.name }).isEqualTo(
        listOf("CN=${server.hostName}", "CN=intermediate_ca", "CN=additional"))
    response1.close()
  }
}
