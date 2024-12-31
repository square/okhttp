package okhttp3.internal.connection

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import okhttp3.Connection
import okhttp3.ConnectionListener
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Route
import okhttp3.internal.http.RealInterceptorChain

/**
 * A connection user that is a specific [RealCall].
 */
internal class CallConnectionUser(
  private val call: RealCall,
  private val poolConnectionListener: ConnectionListener,
  private val chain: RealInterceptorChain,
) : ConnectionUser {
  private val eventListener: EventListener
    get() = call.eventListener

  override fun addPlanToCancel(connectPlan: ConnectPlan) {
    call.plansToCancel += connectPlan
  }

  override fun removePlanToCancel(connectPlan: ConnectPlan) {
    call.plansToCancel -= connectPlan
  }

  override fun updateRouteDatabaseAfterSuccess(route: Route) {
    call.client.routeDatabase.connected(route)
  }

  override fun connectStart(route: Route) {
    eventListener.connectStart(call, route.socketAddress, route.proxy)
    poolConnectionListener.connectStart(route, call)
  }

  override fun connectFailed(
    route: Route,
    protocol: Protocol?,
    e: IOException,
  ) {
    eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
    poolConnectionListener.connectFailed(route, call, e)
  }

  override fun secureConnectStart() {
    eventListener.secureConnectStart(call)
  }

  override fun secureConnectEnd(handshake: Handshake?) {
    eventListener.secureConnectEnd(call, handshake)
  }

  override fun callConnectEnd(
    route: Route,
    protocol: Protocol?,
  ) {
    eventListener.connectEnd(call, route.socketAddress, route.proxy, protocol)
  }

  override fun connectionConnectEnd(
    connection: Connection,
    route: Route,
  ) {
    poolConnectionListener.connectEnd(connection, route, call)
  }

  override fun connectionAcquired(connection: Connection) {
    eventListener.connectionAcquired(call, connection)
  }

  override fun acquireConnectionNoEvents(connection: RealConnection) {
    call.acquireConnectionNoEvents(connection)
  }

  override fun releaseConnectionNoEvents(): Socket? {
    return call.releaseConnectionNoEvents()
  }

  override fun connectionReleased(connection: Connection) {
    eventListener.connectionReleased(call, connection)
  }

  override fun connectionConnectionAcquired(connection: RealConnection) {
    connection.connectionListener.connectionAcquired(connection, call)
  }

  override fun connectionConnectionReleased(connection: RealConnection) {
    connection.connectionListener.connectionReleased(connection, call)
  }

  override fun connectionConnectionClosed(connection: RealConnection) {
    connection.connectionListener.connectionClosed(connection)
  }

  override fun noNewExchanges(connection: RealConnection) {
    connection.connectionListener.noNewExchanges(connection)
  }

  override fun doExtensiveHealthChecks(): Boolean {
    return chain.request.method != "GET"
  }

  override fun isCanceled(): Boolean {
    return call.isCanceled()
  }

  override fun candidateConnection(): RealConnection? {
    return call.connection
  }

  override fun proxySelectStart(url: HttpUrl) {
    eventListener.proxySelectStart(call, url)
  }

  override fun proxySelectEnd(
    url: HttpUrl,
    proxies: List<Proxy>,
  ) {
    eventListener.proxySelectEnd(call, url, proxies)
  }

  override fun dnsStart(socketHost: String) {
    eventListener.dnsStart(call, socketHost)
  }

  override fun dnsEnd(
    socketHost: String,
    result: List<InetAddress>,
  ) {
    eventListener.dnsEnd(call, socketHost, result)
  }
}
