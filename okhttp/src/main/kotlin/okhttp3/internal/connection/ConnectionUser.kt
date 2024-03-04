package okhttp3.internal.connection

import java.io.IOException
import okhttp3.Connection
import okhttp3.Handshake
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

  fun callConnectEnd(route: Route, protocol: Protocol?)

  fun connectionConnectEnd(
      connection: Connection,
      route: Route,
  )

  fun connectFailed(
      route: Route,
      protocol: Protocol?,
      e: IOException
  )

  fun connectionAcquired(connection: Connection)
  fun acquireConnectionNoEvents(connection: RealConnection)
  fun connectionConnectionAcquired(connection: Connection)
}
