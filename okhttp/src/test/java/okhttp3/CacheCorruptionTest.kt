package okhttp3

import okhttp3.internal.io.InMemoryFileSystem
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.CookieManager
import java.net.ResponseCache
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

class CacheCorruptionTest {
  @get:Rule
  var server = MockWebServer()

  @get:Rule
  var fileSystem = InMemoryFileSystem()

  @get:Rule
  val clientTestRule = OkHttpClientTestRule()

  @get:Rule
  val platform = PlatformRule()

  private val handshakeCertificates = localhost()
  private lateinit var client: OkHttpClient
  private lateinit var cache: Cache
  private val NULL_HOSTNAME_VERIFIER =
    HostnameVerifier { name: String?, session: SSLSession? -> true }
  private val cookieManager = CookieManager()

  @Before fun setUp() {
    platform.assumeNotOpenJSSE()
    platform.assumeNotBouncyCastle()
    server.protocolNegotiationEnabled = false
    cache = Cache(File("/cache/"), Int.MAX_VALUE.toLong(), fileSystem)
    client = clientTestRule.newClientBuilder()
      .cache(cache)
      .cookieJar(JavaNetCookieJar(cookieManager))
      .build()
  }

  @After fun tearDown() {
    ResponseCache.setDefault(null)
    if (this::cache.isInitialized) {
      cache.delete()
    }
  }

  @Test @Throws(IOException::class) fun corruptedCacheEntry() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    server.enqueue(MockResponse()
      .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
      .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
      .setBody("ABC"))
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
      .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
      .build()
    val request: Request = Request.Builder().url(server.url("/")).build()
    val response1: Response = client.newCall(request).execute()
    val bodySource = response1.body!!.source()
    Assertions.assertThat(bodySource.readUtf8()).isEqualTo("ABC")

    val metadataFile = fileSystem.files.keys.find { it.name.endsWith(".0") }
    val metadataBuffer = fileSystem.files[metadataFile]

    val contents = metadataBuffer!!.peek().readUtf8()

    metadataBuffer.writeUtf8(contents.substring(0, contents.length / 4))

//    println(metadataBuffer.peek().readUtf8())

    val response2: Response = client.newCall(request).execute() // Cached!
    Assertions.assertThat(response2.body!!.string()).isEqualTo("ABC")
    Assertions.assertThat(cache.requestCount()).isEqualTo(2)
    Assertions.assertThat(cache.networkCount()).isEqualTo(1)
    Assertions.assertThat(cache.hitCount()).isEqualTo(1)
  }

  /**
   * @param delta the offset from the current date to use. Negative values yield dates in the past;
   * positive values yield dates in the future.
   */
  private fun formatDate(delta: Long, timeUnit: TimeUnit): String? {
    return formatDate(Date(System.currentTimeMillis() + timeUnit.toMillis(delta)))
  }

  private fun formatDate(date: Date): String? {
    val rfc1123: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    rfc1123.timeZone = TimeZone.getTimeZone("GMT")
    return rfc1123.format(date)
  }
}