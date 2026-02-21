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
package mockwebserver.socket

public interface SocketEventListener {
    public fun onEvent(event: SocketEvent)

    public companion object {
      public val Noop: SocketEventListener = object : SocketEventListener {
        override fun onEvent(event: SocketEvent) {}
      }
    }
}

public class MemorySocketEventListener(
    private val _events: MutableList<SocketEvent> = mutableListOf()
) : SocketEventListener {
    public val events: List<SocketEvent>
        get() = _events.toList()

    override fun onEvent(event: SocketEvent) {
        _events.add(event)
    }
}
