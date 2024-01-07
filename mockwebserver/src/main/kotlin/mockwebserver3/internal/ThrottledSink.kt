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
package mockwebserver3.internal

import okio.Buffer
import okio.Sink

/**
 * A sink that sleeps [periodDelayNanos] every [bytesPerPeriod] bytes. Unlike [okio.Throttler],
 * this permits any interval to be used.
 */
internal class ThrottledSink(
  private val delegate: Sink,
  private val bytesPerPeriod: Long,
  private val periodDelayNanos: Long,
) : Sink by delegate {
  private var bytesWrittenSinceLastDelay = 0L

  override fun write(
    source: Buffer,
    byteCount: Long,
  ) {
    var bytesLeft = byteCount

    while (bytesLeft > 0) {
      if (bytesWrittenSinceLastDelay == bytesPerPeriod) {
        flush()
        sleepNanos(periodDelayNanos)
        bytesWrittenSinceLastDelay = 0
      }

      val toWrite = minOf(bytesLeft, bytesPerPeriod - bytesWrittenSinceLastDelay)
      bytesWrittenSinceLastDelay += toWrite
      bytesLeft -= toWrite
      delegate.write(source, toWrite)
    }
  }
}
