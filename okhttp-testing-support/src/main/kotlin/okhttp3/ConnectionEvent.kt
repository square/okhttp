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

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.internal.SuppressSignatureCheck

/** Data classes that correspond to each of the methods of [ConnectionListener]. */
@SuppressSignatureCheck
sealed class ConnectionEvent {
  abstract val timestampNs: Long
  open val connection: Connection? = null

  val name: String
    get() = javaClass.simpleName

  data class ConnectStart(
    override val timestampNs: Long,
    val route: Route,
    val call: Call
  ) : ConnectionEvent()

  data class ConnectFailed(
    override val timestampNs: Long,
    val route: Route,
    val call: Call,
    val exception: IOException
  ) : ConnectionEvent()

  data class ConnectEnd(
    override val timestampNs: Long,
    override val connection: Connection,
  ) : ConnectionEvent()

  data class ConnectionClosed(
    override val timestampNs: Long,
    override val connection: Connection,
  ) : ConnectionEvent()

  data class ConnectionAcquired(
    override val timestampNs: Long,
    override val connection: Connection,
    val call: Call
  ) : ConnectionEvent()

  data class ConnectionReleased(
    override val timestampNs: Long,
    override val connection: Connection,
    val call: Call
  ) : ConnectionEvent()

  data class NoNewExchanges(
    override val timestampNs: Long,
    override val connection: Connection,
  ) : ConnectionEvent()
}
