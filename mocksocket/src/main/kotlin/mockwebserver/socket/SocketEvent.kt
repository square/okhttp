/*
 * Copyright (C) 2026 Square, Inc.
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
@file:OptIn(ExperimentalTime::class)

package mockwebserver.socket

import java.net.InetSocketAddress
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

public sealed class SocketEvent {
  public data class SocketConnection(val local: InetSocketAddress, val peer: InetSocketAddress)

        public abstract val timestamp: Instant
        public abstract val threadName: String
        public abstract val socketName: String
        public abstract val connection: SocketConnection

        public data class ReadSuccess(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val byteCount: Long,
                val payload: okio.Buffer? = null,
        ) : SocketEvent()

        public data class ReadFailed(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val reason: String
        ) : SocketEvent()

        public data class ReadWait(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val waitNanos: Long
        ) : SocketEvent()

        public data class ReadEof(
          override val timestamp: Instant,
          override val threadName: String,
          override val socketName: String,
          override val connection: SocketConnection,
        ) : SocketEvent()

        public data class ReadTimeout(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                public val timeoutMs: Int
        ) : SocketEvent()

        public data class TimeoutReached(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                public val message: String
        ) : SocketEvent()

        public data class WriteSuccess(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val byteCount: Long,
                val arrivalTime: Instant,
                val payload: okio.Buffer? = null
        ) : SocketEvent()

        public data class WriteFailed(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val reason: String
        ) : SocketEvent()

        public data class WriteWaitBufferFull(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val bufferSize: Long
        ) : SocketEvent()

        public data class Close(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
        ) : SocketEvent()

        public data class ShutdownInput(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
        ) : SocketEvent()

        public data class ShutdownOutput(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
        ) : SocketEvent()

        public data class Connect(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val host: String?,
                val port: Int
        ) : SocketEvent()

        public data class AcceptStarting(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
        ) : SocketEvent()

        public data class AcceptReturning(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val peerSocketName: String
        ) : SocketEvent()

        public data class DataArrival(
                override val timestamp: Instant,
                override val threadName: String,
                override val socketName: String,
                override val connection: SocketConnection,
                val byteCount: Long,
                val arrivalTime: Instant,
                val payload: okio.Buffer? = null
        ) : SocketEvent()
}
