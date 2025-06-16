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

import java.io.InterruptedIOException
import java.net.Socket

/** Sleeps [nanos], throwing if the socket is closed before that period has elapsed. */
internal fun Socket.sleepWhileOpen(nanos: Long) {
  var ms = nanos / 1_000_000L
  val ns = nanos - (ms * 1_000_000L)

  while (ms > 100) {
    Thread.sleep(100)
    if (isClosed) throw InterruptedIOException("socket closed")
    ms -= 100L
  }

  if (ms > 0L || ns > 0) {
    Thread.sleep(ms, ns.toInt())
  }
}
