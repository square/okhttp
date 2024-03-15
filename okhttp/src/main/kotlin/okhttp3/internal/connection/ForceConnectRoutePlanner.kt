package okhttp3.internal.connection

import okhttp3.Address
import okhttp3.HttpUrl

/**
 * A RoutePlanner that will always generate a ConnectPlan, ignoring any connection pooling
 */
class ForceConnectRoutePlanner(private val delegate: RealRoutePlanner) : RoutePlanner {
  override val address: Address
    get() = delegate.address
  override val deferredPlans: ArrayDeque<RoutePlanner.Plan>
    get() = delegate.deferredPlans

  override fun isCanceled(): Boolean = delegate.isCanceled()

  override fun plan(): RoutePlanner.Plan = delegate.planConnect()

  override fun hasNext(failedConnection: RealConnection?): Boolean = delegate.hasNext(failedConnection)

  override fun sameHostAndPort(url: HttpUrl): Boolean = delegate.sameHostAndPort(url)
}
