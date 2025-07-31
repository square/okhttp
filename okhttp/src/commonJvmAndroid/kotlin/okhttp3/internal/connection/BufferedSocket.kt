/*
 * Copyright (C) 2025 Square, Inc.
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

import java.net.Socket as JavaNetSocket
import okio.BufferedSink
import okio.BufferedSource
import okio.Socket as OkioSocket
import okio.asOkioSocket
import okio.buffer

interface BufferedSocket : OkioSocket {
  override val source: BufferedSource
  override val sink: BufferedSink
}

fun JavaNetSocket.asBufferedSocket(): BufferedSocket = asOkioSocket().asBufferedSocket()

fun OkioSocket.asBufferedSocket(): BufferedSocket =
  object : BufferedSocket {
    private val delegate = this@asBufferedSocket
    override val source = delegate.source.buffer()
    override val sink = delegate.sink.buffer()

    override fun cancel() {
      delegate.cancel()
    }
  }
