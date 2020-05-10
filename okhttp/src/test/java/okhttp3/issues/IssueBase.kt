package okhttp3.issues

import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.RecordedResponse
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS

@Flaky
open class IssueBase {
  val platform = PlatformRule()
  val timeout: TestRule = Timeout(10_000, MILLISECONDS)
  val server = MockWebServer()
  val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = false
    recordEvents = true
    recordTaskRunner = false
  }

  @get:Rule val ruleChain = RuleChain.outerRule(platform).around(server).around(clientTestRule).around(timeout)

  protected val handshakeCertificates = TlsUtil.localhost()
  protected var client = clientTestRule.newClientBuilder()
      .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
      .build()

  @Test
  fun testGetRequest() {
    enableTls()

    server.enqueue(MockResponse().setResponseCode(200).setBody("Hello World!"))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use {
      assertEquals("Hello World!", it.body!!.string())
    }
  }

  fun enableTls() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }
}