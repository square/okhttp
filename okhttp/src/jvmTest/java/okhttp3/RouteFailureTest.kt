package okhttp3


import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.internal.MockWebServerInstance
import okhttp3.internal.http2.ErrorCode
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Flaky
class RouteFailureTest {
  private lateinit var socketFactory: SpecificHostSocketFactory
  private lateinit var client: OkHttpClient

  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server1: MockWebServer
  private lateinit var server2: MockWebServer

  private var listener = RecordingEventListener()

  private val handshakeCertificates = localhost()

  val dns = FakeDns()

  val ipv4 = InetAddress.getByName("192.168.1.1")
  val ipv6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")

  val refusedStream = MockResponse()
    .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
  val bodyResponse = MockResponse().setBody("body")

  @BeforeEach
  fun setUp(
    server: MockWebServer,
    @MockWebServerInstance("server2") server2: MockWebServer
  ) {
    this.server1 = server
    this.server2 = server2

    socketFactory = SpecificHostSocketFactory(InetSocketAddress(server.hostName, server.port))

    client = clientTestRule.newClientBuilder()
      .dns(dns)
      .socketFactory(socketFactory)
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  }

  @Test
  fun http2OneBadHostOneGoodNoRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(false)
      .apply {
        retryOnConnectionFailure = false
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
    assertThat(server2.requestCount).isEqualTo(0)
  }

  @Test
  fun http2OneBadHostOneGoodRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(false)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertBody("body")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    // TODO check if we expect a second request to server1, before attempting server2
    assertThat(server1.requestCount).isEqualTo(2)
    assertThat(server2.requestCount).isEqualTo(1)
  }

  @Test
  fun http2OneBadHostOneGoodNoRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(true)
      .apply {
        retryOnConnectionFailure = false
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
    assertThat(server2.requestCount).isEqualTo(0)
  }

  @Test
  fun http2OneBadHostOneGoodRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(true)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertBody("body")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    // TODO check if we expect a second request to server1, before attempting server2
    assertThat(server1.requestCount).isEqualTo(2)
    assertThat(server2.requestCount).isEqualTo(1)
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. `-Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317`
   */
  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client = client.newBuilder()
      .protocols(listOf(protocol, Protocol.HTTP_1_1))
      .build()
    server1.protocols = client.protocols
    server2.protocols = client.protocols
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    server1.useHttps(handshakeCertificates.sslSocketFactory())
    server2.useHttps(handshakeCertificates.sslSocketFactory())
  }

  private fun executeSynchronously(request: Request): RecordedResponse {
    val call = client.newCall(request)
    return try {
      val response = call.execute()
      val bodyString = response.body.string()
      RecordedResponse(request, response, null, bodyString, null)
    } catch (e: IOException) {
      RecordedResponse(request, null, null, null, e)
    }
  }
}

class SpecificHostSocketFactory(
  val defaultAddress: InetSocketAddress?
) : DelegatingSocketFactory(getDefault()) {
  private val hostMapping = mutableMapOf<InetAddress, InetSocketAddress>()

  /** Sets the results for `hostname`.  */
  operator fun set(
    requested: InetAddress,
    real: InetSocketAddress
  ) {
    hostMapping[requested] = real
  }

  override fun createSocket(): Socket {
    return object : Socket() {
      override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val requested = (endpoint as InetSocketAddress)
        val inetSocketAddress = hostMapping[requested.address] ?: defaultAddress ?: requested
        super.connect(inetSocketAddress, timeout)
      }
    }
  }
}
