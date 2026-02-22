/*
 * Copyright (c) 2026 Block, Inc.
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

import java.io.Closeable
import kotlin.time.ExperimentalTime
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer

public class NetLogRecorder(
  file: Path,
  fileSystem: FileSystem = FileSystem.SYSTEM,
) : SocketEventListener,
  Closeable {
  private val writer = fileSystem.sink(file).buffer()
  private var isFirstEvent = true
  private var closed = false

  init {
    writer.println("{")
    writer.println("  \"constants\": {},")
    writer.println("  \"events\": [")
    writer.flush()
  }

  override fun onEvent(event: SocketEvent) {
    val time = event.timestamp.toEpochMilliseconds()

    val jsonEvent =
      when (event) {
        is SocketEvent.Connect -> {
          """
          {
            "phase": 1,
            "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
            "time": "$time",
            "type": 67,
            "params": {
              "address": "${event.host}:${event.port}"
            }
          }
          """.trimIndent()
        }

        is SocketEvent.ReadSuccess -> {
          // Not recording actual base64 payload to save memory, just counts
          """
          {
            "phase": 0,
            "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
            "time": "$time",
            "type": 113,
            "params": { "byte_count": ${event.byteCount} }
          }
          """.trimIndent()
        }

        is SocketEvent.WriteSuccess -> {
          """
          {
            "phase": 0,
            "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
            "time": "$time",
            "type": 114,
            "params": { "byte_count": ${event.byteCount} }
          }
          """.trimIndent()
        }

        is SocketEvent.Close -> {
          """
          {
            "phase": 2,
            "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
            "time": "$time",
            "type": 67
          }
          """.trimIndent()
        }

        else -> {
          null
        }
      }

    if (jsonEvent != null) {
      synchronized(this) {
        if (!isFirstEvent) {
          writer.println(",")
        }
        isFirstEvent = false
        writer.writeUtf8(jsonEvent.replace("\n", "\n    "))
        writer.flush()
      }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    if (!isFirstEvent) writer.writeUtf8("\n")
    writer.println("  ]")
    writer.println("}")
    writer.close()
  }
}

private fun BufferedSink.println(string: String) {
  writeUtf8(string)
  writeUtf8("\n")
}
