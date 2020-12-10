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
package okhttp.android.test.tls;

import android.os.Build
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import okhttp3.tls.internal.TlsUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.security.cert.X509Certificate

/**
 * Test for new Let's Encrypt Root Certificate.
 */
@ExtendWith(MockWebServerExtension::class)
class PrivateKeyClientTest(val server: MockWebServer) {
  @Test
  fun get() {
    val clientCert = HeldCertificate.Builder()
      .serialNumber(1L)
      .commonName("Jethro Willis")
      .addSubjectAlternativeName("jethrowillis.com")
      .build()

    val localhost = TlsUtil.localhost()

    server.useHttps(localhost.sslSocketFactory())

    val handshakeCertificates = HandshakeCertificates.Builder()
      .addPlatformTrustedCertificates()
//      .addClientCertificate(clientCert)
      .build()

    val client = OkHttpClient.Builder().build()

    val request = Request.Builder()
      .url(server.url("/"))
      .build()

    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
    }
  }
}