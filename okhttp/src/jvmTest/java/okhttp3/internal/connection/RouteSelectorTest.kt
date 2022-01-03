/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.FakeDns
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import okhttp3.internal.connection.RouteSelector.Companion.socketHost
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion.majorVersion
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RouteSelectorTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  val connectionSpecs = listOf(
    ConnectionSpec.MODERN_TLS,
    ConnectionSpec.COMPATIBLE_TLS,
    ConnectionSpec.CLEARTEXT
  )

  private val uriHost = "hosta"
  private val uriPort = 1003
  private lateinit var call: Call
  private lateinit var socketFactory: SocketFactory
  private val handshakeCertificates = localhost()
  private val sslSocketFactory = handshakeCertificates.sslSocketFactory()
  private lateinit var hostnameVerifier: HostnameVerifier
  private val authenticator = Authenticator.NONE
  private val protocols = listOf(Protocol.HTTP_1_1)
  private val dns = FakeDns()
  private val proxySelector = RecordingProxySelector()
  private val routeDatabase = RouteDatabase()

  @BeforeEach fun setUp() {
    call = clientTestRule.newClient().newCall(
      Request.Builder()
        .url("https://$uriHost:$uriPort/")
        .build()
    )
    socketFactory = SocketFactory.getDefault()
    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
  }

  @Test fun singleRoute() {
    val address = httpAddress()
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse
    try {
      selection.next()
      fail<Any>()
    } catch (expected: NoSuchElementException) {
    }
    assertThat(routeSelector.hasNext()).isFalse
    try {
      routeSelector.next()
      fail<Any>()
    } catch (expected: NoSuchElementException) {
    }
  }

  @Test fun singleRouteReturnsFailedRoute() {
    val address = httpAddress()
    var routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    var selection = routeSelector.next()
    val route = selection.next()
    routeDatabase.failed(route)
    routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    assertThat(selection.hasNext()).isFalse
    try {
      selection.next()
      fail<Any>()
    } catch (expected: NoSuchElementException) {
    }
    assertThat(routeSelector.hasNext()).isFalse
    try {
      routeSelector.next()
      fail<Any>()
    } catch (expected: NoSuchElementException) {
    }
  }

  @Test fun explicitProxyTriesThatProxysAddressesOnly() {
    val address = Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = proxyA,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector
    )
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[proxyAHost] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    assertRoute(selection.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
    dns.assertRequests(proxyAHost)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  @Test fun explicitDirectProxy() {
    val address = Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = Proxy.NO_PROXY,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector
    )
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 1), uriPort)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
    dns.assertRequests(uriHost)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  /**
   * Don't call through to the proxy selector if we don't have a host name.
   * https://github.com/square/okhttp/issues/5770
   */
  @Test fun proxySelectorNotCalledForNullHost() {
    // The string '>' is okay in a hostname in HttpUrl, which does very light hostname validation.
    // It is not okay in URI, and so it's stripped and we get a URI with a null host.
    val bogusHostname = ">"
    val address = Address(
      uriHost = bogusHostname,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = null,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector
    )
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[bogusHostname] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(bogusHostname, 0), uriPort)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
    dns.assertRequests(bogusHostname)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  @Test fun proxySelectorReturnsNull() {
    val nullProxySelector: ProxySelector = object : ProxySelector() {
      override fun select(uri: URI): List<Proxy>? {
        assertThat(uri.host).isEqualTo(uriHost)
        return null
      }

      override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {
        throw AssertionError()
      }
    }

    val address = Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = null,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = nullProxySelector
    )
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun proxySelectorReturnsNoProxies() {
    val address = httpAddress()
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 1), uriPort)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
    dns.assertRequests(uriHost)
    proxySelector.assertRequests(address.url.toUri())
  }

  @Test fun proxySelectorReturnsMultipleProxies() {
    val address = httpAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    proxySelector.assertRequests(address.url.toUri())

    // First try the IP addresses of the first proxy, in sequence.
    assertThat(routeSelector.hasNext()).isTrue
    dns[proxyAHost] = dns.allocate(2)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort)
    dns.assertRequests(proxyAHost)
    assertThat(selection1.hasNext()).isFalse

    // Next try the IP address of the second proxy.
    assertThat(routeSelector.hasNext()).isTrue
    dns[proxyBHost] = dns.allocate(1)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort)
    dns.assertRequests(proxyBHost)
    assertThat(selection2.hasNext()).isFalse

    // No more proxies to try.
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun proxySelectorDirectConnectionsAreSkipped() {
    val address = httpAddress()
    proxySelector.proxies.add(Proxy.NO_PROXY)
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    proxySelector.assertRequests(address.url.toUri())

    // Only the origin server will be attempted.
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun proxyDnsFailureContinuesToNextProxy() {
    val address = httpAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    proxySelector.proxies.add(proxyA)
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    proxySelector.assertRequests(address.url.toUri())
    assertThat(routeSelector.hasNext()).isTrue
    dns[proxyAHost] = dns.allocate(1)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    dns.assertRequests(proxyAHost)
    assertThat(selection1.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isTrue
    dns.clear(proxyBHost)
    try {
      routeSelector.next()
      fail<Any>()
    } catch (expected: UnknownHostException) {
    }
    dns.assertRequests(proxyBHost)
    assertThat(routeSelector.hasNext()).isTrue
    dns[proxyAHost] = dns.allocate(1)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    dns.assertRequests(proxyAHost)
    assertThat(selection2.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun multipleProxiesMultipleInetAddressesMultipleConfigurations() {
    val address = httpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)

    // Proxy A
    dns[proxyAHost] = dns.allocate(2)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    dns.assertRequests(proxyAHost)
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort)
    assertThat(selection1.hasNext()).isFalse

    // Proxy B
    dns[proxyBHost] = dns.allocate(2)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort)
    dns.assertRequests(proxyBHost)
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 1), proxyBPort)
    assertThat(selection2.hasNext()).isFalse

    // No more proxies to attempt.
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun failedRouteWithSingleProxy() {
    val address = httpsAddress()
    var routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    val numberOfAddresses = 2
    dns[uriHost] = dns.allocate(numberOfAddresses)

    // Extract the regular sequence of routes from selector.
    val selection1 = routeSelector.next()
    val regularRoutes = selection1.routes

    // Check that we do indeed have more than one route.
    assertThat(regularRoutes.size).isEqualTo(numberOfAddresses)
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes[0])
    // Reset selector
    routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)

    // The first selection prioritizes the non-failed routes.
    val selection2 = routeSelector.next()
    assertThat(selection2.next()).isEqualTo(regularRoutes[1])
    assertThat(selection2.hasNext()).isFalse

    // The second selection will contain all failed routes.
    val selection3 = routeSelector.next()
    assertThat(selection3.next()).isEqualTo(regularRoutes[0])
    assertThat(selection3.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun failedRouteWithMultipleProxies() {
    val address = httpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    var routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    dns[proxyAHost] = dns.allocate(1)
    dns[proxyBHost] = dns.allocate(1)

    // Mark the ProxyA route as failed.
    val selection = routeSelector.next()
    dns.assertRequests(proxyAHost)
    val route = selection.next()
    assertRoute(route, address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    routeDatabase.failed(route)
    routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)

    // Confirm we enumerate both proxies, giving preference to the route from ProxyB.
    val selection2 = routeSelector.next()
    dns.assertRequests(proxyAHost, proxyBHost)
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort)
    assertThat(selection2.hasNext()).isFalse

    // Confirm the last selection contains the postponed route from ProxyA.
    val selection3 = routeSelector.next()
    dns.assertRequests()
    assertRoute(selection3.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    assertThat(selection3.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun queryForAllSelectedRoutes() {
    val address = httpAddress()
    val routeSelector = RouteSelector(address, routeDatabase, call, EventListener.NONE)
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    dns.assertRequests(uriHost)
    val routes = selection.routes
    assertRoute(routes[0], address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    assertRoute(routes[1], address, Proxy.NO_PROXY, dns.lookup(uriHost, 1), uriPort)
    assertThat(selection.next()).isSameAs(routes[0])
    assertThat(selection.next()).isSameAs(routes[1])
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun getHostString() {
    // Name proxy specification.
    var socketAddress = InetSocketAddress.createUnresolved("host", 1234)
    assertThat(socketAddress.socketHost).isEqualTo("host")
    socketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")

    // InetAddress proxy specification.
    socketAddress = InetSocketAddress(InetAddress.getByName("localhost"), 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
    socketAddress = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
    socketAddress = InetSocketAddress(
      InetAddress.getByAddress("foobar", byteArrayOf(127, 0, 0, 1)),
      1234
    )
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
  }

  @Test fun routeToString() {
    val route = Route(
      httpAddress(),
      Proxy.NO_PROXY,
      InetSocketAddress.createUnresolved("host", 1234)
    )
    val expected = when {
      isAndroid || majorVersion < 14 -> "Route{host:1234}"
      else -> "Route{host/<unresolved>:1234}"
    }
    assertThat(route.toString()).isEqualTo(expected)
  }

  private fun assertRoute(
    route: Route,
    address: Address,
    proxy: Proxy,
    socketAddress: InetAddress,
    socketPort: Int
  ) {
    assertThat(route.address).isEqualTo(address)
    assertThat(route.proxy).isEqualTo(proxy)
    assertThat(route.socketAddress.address).isEqualTo(socketAddress)
    assertThat(route.socketAddress.port).isEqualTo(socketPort)
  }

  /** Returns an address that's without an SSL socket factory or hostname verifier.  */
  private fun httpAddress(): Address {
    return Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = null,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector
    )
  }

  private fun httpsAddress(): Address {
    return Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = sslSocketFactory,
      hostnameVerifier = hostnameVerifier,
      certificatePinner = null,
      proxyAuthenticator = authenticator,
      proxy = null,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector
    )
  }

  companion object {
    private const val proxyAPort = 1001
    private const val proxyAHost = "proxya"
    private val proxyA = Proxy(
      Proxy.Type.HTTP,
      InetSocketAddress.createUnresolved(proxyAHost, proxyAPort)
    )

    private const val proxyBPort = 1002
    private const val proxyBHost = "proxyb"
    private val proxyB = Proxy(
      Proxy.Type.HTTP,
      InetSocketAddress.createUnresolved(proxyBHost, proxyBPort)
    )
  }
}
