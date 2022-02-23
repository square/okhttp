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
import okhttp3.Address
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FakeDns
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.Route
import okhttp3.TestValueFactory
import okhttp3.internal.connection.RouteSelector.Companion.socketHost
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RouteSelectorTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val dns = FakeDns()
  private val proxySelector = RecordingProxySelector()
  private val uriHost = "hosta"
  private val uriPort = 1003
  private val factory = TestValueFactory().apply {
    this.dns = this@RouteSelectorTest.dns
    this.proxySelector = this@RouteSelectorTest.proxySelector
    this.uriHost = this@RouteSelectorTest.uriHost
    this.uriPort = this@RouteSelectorTest.uriPort
  }

  private lateinit var call: Call
  private val routeDatabase = RouteDatabase()

  @BeforeEach fun setUp() {
    call = clientTestRule.newClient().newCall(
      Request.Builder()
        .url("https://$uriHost:$uriPort/")
        .build()
    )
  }

  @AfterEach fun tearDown() {
    factory.close()
  }

  @Test fun singleRoute() {
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress()
    var routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    var selection = routeSelector.next()
    val route = selection.next()
    routeDatabase.failed(route)
    routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress(
      proxy = proxyA,
    )
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress(
      proxy = Proxy.NO_PROXY,
    )
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress(
      uriHost = bogusHostname,
      uriPort = uriPort,
    )
    val routeSelector = newRouteSelector(address)
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

    val address = factory.newAddress(
      proxySelector = nullProxySelector
    )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns.lookup(uriHost, 0), uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse
    assertThat(routeSelector.hasNext()).isFalse
  }

  @Test fun proxySelectorReturnsNoProxies() {
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress()
    proxySelector.proxies.add(Proxy.NO_PROXY)
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    proxySelector.proxies.add(proxyA)
    val routeSelector = newRouteSelector(address)
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
    val address = factory.newHttpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = newRouteSelector(address)

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
    val address = factory.newHttpsAddress()
    var routeSelector = newRouteSelector(address)
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
    routeSelector = newRouteSelector(address)

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
    val address = factory.newHttpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    var routeSelector = newRouteSelector(address)
    dns[proxyAHost] = dns.allocate(1)
    dns[proxyBHost] = dns.allocate(1)

    // Mark the ProxyA route as failed.
    val selection = routeSelector.next()
    dns.assertRequests(proxyAHost)
    val route = selection.next()
    assertRoute(route, address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort)
    routeDatabase.failed(route)
    routeSelector = newRouteSelector(address)

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
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
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

  @Test fun addressesNotSortedWhenFastFallbackIsOff() {
    val address = factory.newAddress(
      proxy = Proxy.NO_PROXY
    )
    val routeSelector = newRouteSelector(
      address = address,
      fastFallback = false,
    )
    assertThat(routeSelector.hasNext()).isTrue()
    val (ipv4_1, ipv4_2) = dns.allocate(2)
    val (ipv6_1, ipv6_2) = dns.allocateIpv6(2)
    dns[uriHost] = listOf(ipv4_1, ipv4_2, ipv6_1, ipv6_2)

    val selection = routeSelector.next()
    assertThat(selection.routes.map { it.socketAddress.address }).containsExactly(
      ipv4_1,
      ipv4_2,
      ipv6_1,
      ipv6_2,
    )
  }

  @Test fun addressesSortedWhenFastFallbackIsOn() {
    val address = factory.newAddress(
      proxy = Proxy.NO_PROXY
    )
    val routeSelector = newRouteSelector(
      address = address,
      fastFallback = true,
    )
    assertThat(routeSelector.hasNext()).isTrue()
    val (ipv4_1, ipv4_2) = dns.allocate(2)
    val (ipv6_1, ipv6_2) = dns.allocateIpv6(2)
    dns[uriHost] = listOf(ipv4_1, ipv4_2, ipv6_1, ipv6_2)

    val selection = routeSelector.next()
    assertThat(selection.routes.map { it.socketAddress.address }).containsExactly(
      ipv6_1,
      ipv4_1,
      ipv6_2,
      ipv4_2,
    )
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
    val ipv4Address = InetAddress.getByAddress(
      byteArrayOf(1, 2, 3, 4)
    )
    assertThat(
      Route(
        factory.newAddress(uriHost = "1.2.3.4", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv4Address, 1003)
      ).toString()
    ).isEqualTo("1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv4Address, 1003)
      ).toString()
    ).isEqualTo("example.com at 1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv4Address, 1003)
      ).toString()
    ).isEqualTo("example.com via proxy 1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv4Address, 5678)
      ).toString()
    ).isEqualTo("example.com:1003 via proxy 1.2.3.4:5678")
  }

  @Test fun routeToStringIpv6() {
    val ipv6Address = InetAddress.getByAddress(
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    )
    assertThat(
      Route(
        factory.newAddress(uriHost = "::1", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv6Address, uriPort)
      ).toString()
    ).isEqualTo("[::1]:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv6Address, uriPort)
      ).toString()
    ).isEqualTo("example.com at [::1]:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv6Address, 5678)
      ).toString()
    ).isEqualTo("example.com:1003 via proxy [::1]:5678")
    assertThat(
      Route(
        factory.newAddress(uriHost = "::2", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv6Address, 5678)
      ).toString()
    ).isEqualTo("[::2]:1003 via proxy [::1]:5678")
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

  private fun newRouteSelector(
    address: Address,
    routeDatabase: RouteDatabase = this.routeDatabase,
    fastFallback: Boolean = false,
    call: Call = this.call,
  ): RouteSelector {
    return RouteSelector(
      address = address,
      routeDatabase = routeDatabase,
      call = call,
      fastFallback = fastFallback,
      eventListener = EventListener.NONE,
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
