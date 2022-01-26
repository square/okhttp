/*
 * Copyright (C) 2015 Square, Inc.
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

internal class ExchangeFinder(
  private val routePlanner: RoutePlanner
) {
  fun find(): RealConnection {
    var firstException: IOException? = null
    while (true) {
      if (routePlanner.isCanceled()) throw IOException("Canceled")

      try {
        val plan = routePlanner.plan()
        if (!plan.isConnected) {
          plan.connect()
        }
        return plan.handleSuccess()
      } catch (e: IOException) {
        routePlanner.trackFailure(e)

        if (firstException == null) {
          firstException = e
        } else {
          firstException.addSuppressed(e)
        }
        if (!routePlanner.hasMoreRoutes()) {
          throw firstException
        }
      }
    }
  }
}
