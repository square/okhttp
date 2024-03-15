package okhttp3.internal.connection

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Route

/**
 * A user that is a connection pool creating connections in the background
 * without an intent to immediately use them.
 *
 * Most of this class's functionality is currently stubbed out, but will be revisited in the future.
 */
class PoolConnectionUser : ConnectionUser {
  override fun addPlanToCancel(connectPlan: ConnectPlan) {
  }

  override fun removePlanToCancel(connectPlan: ConnectPlan) {
  }

  override fun updateRouteDatabaseAfterSuccess(route: Route) {
  }

  override fun connectStart(route: Route) {
  }

  override fun secureConnectStart() {
  }

  override fun secureConnectEnd(handshake: Handshake?) {
  }

  override fun callConnectEnd(
    route: Route,
    protocol: Protocol?,
  ) {
  }

  override fun connectionConnectEnd(
    connection: Connection,
    route: Route,
  ) {
  }

  override fun connectFailed(
    route: Route,
    protocol: Protocol?,
    e: IOException,
  ) {
  }

  override fun connectionAcquired(connection: Connection) {
  }

  override fun acquireConnectionNoEvents(connection: RealConnection) {
  }

  override fun releaseConnectionNoEvents(): Socket? {
    return null
  }

  override fun connectionReleased(connection: Connection) {
  }

  override fun connectionConnectionAcquired(connection: RealConnection) {
  }

  override fun connectionConnectionReleased(connection: RealConnection) {
  }

  override fun connectionConnectionClosed(connection: RealConnection) {
  }

  override fun noNewExchanges(connection: RealConnection) {
  }

  override fun doExtensiveHealthChecks(): Boolean = false

  override fun isCanceled(): Boolean = false

  override fun candidateConnection(): RealConnection? = null

  override fun proxySelectStart(url: HttpUrl) {
  }

  override fun proxySelectEnd(
    url: HttpUrl,
    proxies: List<Proxy>,
  ) {
  }

  override fun dnsStart(socketHost: String) {
  }

  override fun dnsEnd(
    socketHost: String,
    result: List<InetAddress>,
  ) {
  }
}
