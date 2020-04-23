package okhttp3.internal.authenticator

import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.SocketFactory
import okhttp3.Address
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.FakeDns
import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.internal.RecordingAuthenticator
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.tls.internal.TlsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

// Most tests from URLConnectionTest
class JavaNetAuthenticatorTest {
  var authenticator = JavaNetAuthenticator()

  val fakeDns = FakeDns()

  val recordingAuthenticator = RecordingAuthenticator()

  @Before
  fun setup() {
    Authenticator.setDefault(recordingAuthenticator)
  }

  @After
  fun tearDown() {
    Authenticator.setDefault(null)
  }

  @Test
  fun testBasicAuth() {
    fakeDns["server"] = listOf(InetAddress.getLocalHost())

    val address = Address(
        "server", 443, fakeDns, SocketFactory.getDefault(), TlsUtil.localhost()
        .sslSocketFactory(), OkHostnameVerifier, CertificatePinner.DEFAULT,
        okhttp3.Authenticator.NONE, Proxy.NO_PROXY, listOf(HTTP_1_1),
        listOf(ConnectionSpec.MODERN_TLS), NullProxySelector
    )
    val route = Route(address, Proxy.NO_PROXY, InetSocketAddress.createUnresolved("server", 443))

    val request = Request.Builder()
        .url("https://server/robots.txt")
        .build()
    val response = Response.Builder()
        .request(request)
        .code(401)
        .header("WWW-Authenticate", "Basic realm=\"User Visible Realm\"")
        .protocol(HTTP_2)
        .message("Unauthorized")
        .build()
    val authRequest = authenticator.authenticate(route, response)

    assertNotNull(authRequest)
    assertEquals(
        "Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}", authRequest!!.header("Authorization")
    )
  }
}
