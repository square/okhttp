package okhttp3

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.openjsse.net.ssl.OpenJSSE
import java.net.InetAddress
import java.security.Security

class OpenJSSETest {
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()
  @JvmField @Rule val server = MockWebServer()
  lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    Security.insertProviderAt(OpenJSSE(), 1)
    client = clientTestRule.newClient()
  }

  @After
  fun cleanup() {
    Security.removeProvider("OpenJSSE")
  }

  @Test
  fun testX() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      Assert.assertEquals(200, response.code)
    }
  }

  private fun enableTls() {
    // Generate a self-signed cert for the server to serve and the client to trust.
    val heldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
        .build()
    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }
}