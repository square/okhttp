/*
 * Copyright (C) 2013 Square, Inc.
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

import okhttp3.Route
import okhttp3.internal.removeFirst

/**
 * A blacklist of failed routes to avoid when creating a new connection to a target address. This is
 * used so that OkHttp can learn from its mistakes: if there was a failure attempting to connect to
 * a specific IP address or proxy server, that failure is remembered and alternate routes are
 * preferred.
 */
class RouteDatabase {
  /**
   * Don't grow unbounded, especially since the [SSLSocketFactory] instances held by [Route] may
   * be quite large: 270 KiB according to a report on issue 5746.
   */
  private val maxSize = 128

  private val failedRoutes = mutableSetOf<Route>()

  /** Records a failure connecting to [failedRoute]. */
  @Synchronized fun failed(failedRoute: Route) {
    if (failedRoutes.size == maxSize) {
      failedRoutes.removeFirst()
    }
    failedRoutes.add(failedRoute)
  }

  /** Records success connecting to [route]. */
  @Synchronized fun connected(route: Route) {
    failedRoutes.remove(route)
  }

  /** Returns true if [route] has failed recently and should be avoided. */
  @Synchronized fun shouldPostpone(route: Route): Boolean = route in failedRoutes
}
