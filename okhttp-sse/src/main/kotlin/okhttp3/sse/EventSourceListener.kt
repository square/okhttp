/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.sse

import okhttp3.Response

abstract class EventSourceListener {
  /**
   * Invoked when an event source has been accepted by the remote peer and may begin transmitting
   * events.
   */
  open fun onOpen(eventSource: EventSource, response: Response) {
  }

  /**
   * TODO description.
   */
  open fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
  }

  /**
   * TODO description.
   *
   * No further calls to this listener will be made.
   */
  open fun onClosed(eventSource: EventSource) {
  }

  /**
   * Invoked when an event source has been closed due to an error reading from or writing to the
   * network. Incoming events may have been lost. No further calls to this listener will be made.
   */
  open fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
  }
}
