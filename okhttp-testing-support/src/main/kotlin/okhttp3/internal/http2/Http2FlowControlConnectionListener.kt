/*
 * Copyright (C) 2023 Block, Inc.
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
package okhttp3.internal.http2

import okhttp3.ConnectionListener
import okhttp3.internal.http2.flowcontrol.WindowCounter

/**
 * ConnectionListener that outputs CSV for flow control of client receiving streams.
 */
class Http2FlowControlConnectionListener : ConnectionListener(), FlowControlListener {
  val start = System.currentTimeMillis()

  override fun receivingStreamWindowChanged(
    streamId: Int,
    windowCounter: WindowCounter,
    bufferSize: Long,
  ) {
    println("${System.currentTimeMillis() - start},$streamId,${windowCounter.unacknowledged},$bufferSize")
  }

  override fun receivingConnectionWindowChanged(windowCounter: WindowCounter) {
    println("${System.currentTimeMillis() - start},0,${windowCounter.unacknowledged},")
  }
}
