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
package okhttp3.sse.internal

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import java.util.ArrayDeque
import java.util.Deque
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ServerSentEventIteratorTest {
  /** Either [Event] or [Long] items for events and retry changes, respectively.  */
  private val callbacks: Deque<Any> = ArrayDeque()

  @AfterEach
  fun after() {
    assertThat(callbacks).isEmpty()
  }

  @Test
  fun multiline() {
    consumeEvents(
      """
      |data: YHOO
      |data: +2
      |data: 10
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "YHOO\n+2\n10"))
  }

  @Test
  fun multilineCr() {
    consumeEvents(
      """
      |data: YHOO
      |data: +2
      |data: 10
      |
      |
      """.trimMargin().replace("\n", "\r"),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "YHOO\n+2\n10"))
  }

  @Test
  fun multilineCrLf() {
    consumeEvents(
      """
      |data: YHOO
      |data: +2
      |data: 10
      |
      |
      """.trimMargin().replace("\n", "\r\n"),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "YHOO\n+2\n10"))
  }

  @Test
  fun eventType() {
    consumeEvents(
      """
      |event: add
      |data: 73857293
      |
      |event: remove
      |data: 2153
      |
      |event: add
      |data: 113411
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, "add", "73857293"))
    assertThat(callbacks.remove()).isEqualTo(Event(null, "remove", "2153"))
    assertThat(callbacks.remove()).isEqualTo(Event(null, "add", "113411"))
  }

  @Test
  fun commentsIgnored() {
    consumeEvents(
      """
      |: test stream
      |
      |data: first event
      |id: 1
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "first event"))
  }

  @Test
  fun idCleared() {
    consumeEvents(
      """
      |data: first event
      |id: 1
      |
      |data: second event
      |id
      |
      |data: third event
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "first event"))
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "second event"))
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "third event"))
  }

  @Test
  fun nakedFieldNames() {
    consumeEvents(
      """
      |data
      |
      |data
      |data
      |
      |data:
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, ""))
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "\n"))
  }

  @Test
  fun colonSpaceOptional() {
    consumeEvents(
      """
      |data:test
      |
      |data: test
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "test"))
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "test"))
  }

  @Test
  fun leadingWhitespace() {
    consumeEvents(
      """
      |data:  test
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, " test"))
  }

  @Test
  fun idReusedAcrossEvents() {
    consumeEvents(
      """
      |data: first event
      |id: 1
      |
      |data: second event
      |
      |id: 2
      |data: third event
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "first event"))
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "second event"))
    assertThat(callbacks.remove()).isEqualTo(Event("2", null, "third event"))
  }

  @Test
  fun idIgnoredFromEmptyEvent() {
    consumeEvents(
      """
      |data: first event
      |id: 1
      |
      |id: 2
      |
      |data: second event
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "first event"))
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "second event"))
  }

  @Test
  fun retry() {
    consumeEvents(
      """
      |retry: 22
      |
      |data: first event
      |id: 1
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(22L)
    assertThat(callbacks.remove()).isEqualTo(Event("1", null, "first event"))
  }

  @Test
  fun retryInvalidFormatIgnored() {
    consumeEvents(
      """
      |retry: 22
      |
      |retry: hey
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(22L)
  }

  @Test
  fun namePrefixIgnored() {
    consumeEvents(
      """
      |data: a
      |eventually
      |database
      |identity
      |retrying
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "a"))
  }

  @Test
  fun nakedNameClearsIdAndTypeAppendsData() {
    consumeEvents(
      """
      |id: a
      |event: b
      |data: c
      |id
      |event
      |data
      |
      |
      """.trimMargin(),
    )
    assertThat(callbacks.remove()).isEqualTo(Event(null, null, "c\n"))
  }

  @Test
  fun nakedRetryIgnored() {
    consumeEvents(
      """
      |retry
      |
      """.trimMargin(),
    )
    assertThat(callbacks).isEmpty()
  }

  private fun consumeEvents(source: String) {
    val callback: ServerSentEventReader.Callback =
      object : ServerSentEventReader.Callback {
        override fun onEvent(
          id: String?,
          type: String?,
          data: String,
        ) {
          callbacks.add(Event(id, type, data))
        }

        override fun onRetryChange(timeMs: Long) {
          callbacks.add(timeMs)
        }
      }
    val buffer = Buffer().writeUtf8(source)
    val reader = ServerSentEventReader(buffer, callback)
    while (reader.processNextEvent()) {
    }
    assertThat(buffer.size, "Unconsumed buffer: ${buffer.readUtf8()}")
      .isEqualTo(0)
  }
}
