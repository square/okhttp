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
 * The object that is asking for a connection. Either a call or a connect policy from the pool.
 */
interface ConnectionUser {
  fun addPlanToCancel(connectPlan: ConnectPlan)

  fun removePlanToCancel(connectPlan: ConnectPlan)

  fun updateRouteDatabaseAfterSuccess(route: Route)

  fun connectStart(route: Route)

  fun secureConnectStart()

  fun secureConnectEnd(handshake: Handshake?)

  fun callConnectEnd(
    route: Route,
    protocol: Protocol?,
  )

  fun connectionConnectEnd(
    connection: Connection,
    route: Route,
  )

  fun connectFailed(
    route: Route,
    protocol: Protocol?,
    e: IOException,
  )

  fun connectionAcquired(connection: Connection)

  fun acquireConnectionNoEvents(connection: RealConnection)

  fun releaseConnectionNoEvents(): Socket?

  fun connectionReleased(connection: Connection)

  fun connectionConnectionAcquired(connection: RealConnection)

  fun connectionConnectionReleased(connection: RealConnection)

  fun connectionConnectionClosed(connection: RealConnection)

  fun noNewExchanges(connection: RealConnection)

  fun doExtensiveHealthChecks(): Boolean

  fun isCanceled(): Boolean

  fun candidateConnection(): RealConnection?

  fun proxySelectStart(url: HttpUrl)

  fun proxySelectEnd(
    url: HttpUrl,
    proxies: List<Proxy>,
  )

  fun dnsStart(socketHost: String)

  fun dnsEnd(
    socketHost: String,
    result: List<InetAddress>,
  )
}
