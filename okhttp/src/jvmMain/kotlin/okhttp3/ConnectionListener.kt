/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3

import java.net.ProtocolException
import okio.IOException

/**
 * Listener for connection events. Extend this class to monitor the new connections and closes.
 *
 * All event methods must execute fast, without external locking, cannot throw exceptions,
 * attempt to mutate the event parameters, or be reentrant back into the client.
 * Any IO - writing to files or network should be done asynchronously.
 */
abstract class ConnectionListener {
  open fun connectionOpening(route: Route) {}
  open fun connectFailed(route: Route, failure: IOException) {}
  open fun connectionOpened(connection: Connection) {}
  open fun connectionClosed(connection: Connection) {}
  open fun connectionAcquired(connection: Connection, call: Call) {}
  open fun connectionReleased(connection: Connection, call: Call) {}
  open fun noNewExchanges(connection: Connection) {}

  companion object {
    val NONE: ConnectionListener = object : ConnectionListener() {}
  }
}
