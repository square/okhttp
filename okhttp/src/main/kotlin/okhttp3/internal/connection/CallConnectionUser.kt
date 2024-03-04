package okhttp3.internal.connection

import java.io.IOException
import okhttp3.Connection
import okhttp3.ConnectionListener
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Route

internal class CallConnectionUser(
  val call: RealCall,
  val connectionListener: ConnectionListener,
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
    connectionListener.connectStart(route, call)
  }

  override fun connectFailed(
    route: Route,
    protocol: Protocol?,
    e: IOException,
  ) {
    eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
    connectionListener.connectFailed(route, call, e)
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
    connectionListener.connectEnd(connection, route, call)
  }

  override fun connectionAcquired(connection: Connection) {
    eventListener.connectionAcquired(call, connection)
  }

  override fun acquireConnectionNoEvents(connection: RealConnection) {
    call.acquireConnectionNoEvents(connection)
  }

  override fun connectionConnectionAcquired(connection: Connection) {
    // TODO: verify that this == connection.connectionListener.
    connectionListener.connectionAcquired(connection, call)
  }
}
