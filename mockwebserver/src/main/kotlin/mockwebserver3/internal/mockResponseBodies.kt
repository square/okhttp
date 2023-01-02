/*
 * Copyright (c) 2022 Block, Inc.
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
 *
 */
@file:JvmName("MockResponseBodiesKt")

package mockwebserver3.internal

import mockwebserver3.MockResponseBody
import okio.Buffer
import okio.BufferedSink

internal fun Buffer.toMockResponseBody(): MockResponseBody {
  val defensiveCopy = clone()
  return BufferMockResponseBody(defensiveCopy)
}

internal class BufferMockResponseBody(
  val buffer: Buffer,
) : MockResponseBody {
  override val contentLength = buffer.size

  override fun writeTo(sink: BufferedSink) {
    buffer.copyTo(sink.buffer)
    sink.emitCompleteSegments()
  }
}
